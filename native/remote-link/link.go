package main

/*
#cgo android LDFLAGS: -landroid
#include <stdlib.h>
#include <android/multinetwork.h>
#include <errno.h>
static int thor_bind_socket(unsigned long long network, int fd) {
    if (android_setsocknetwork((net_handle_t)network, fd) == 0) return 0;
    return errno;
}
*/
import "C"

import (
	"context"
	"crypto/sha256"
	"crypto/tls"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"
	"unsafe"

	"tailscale.com/net/netmon"
	"tailscale.com/net/netns"
	"tailscale.com/tsnet"
)

var guard sync.Mutex
var node *tsnet.Server
var androidInterfaces []netmon.Interface
var peerHost string
var lanAddress string
var underlayNetwork atomic.Uint64
var interfaceSnapshot string

func bindUnderlay(fd int) error {
	handle := underlayNetwork.Load()
	if handle == 0 {
		return nil
	}
	if code := C.thor_bind_socket(C.ulonglong(handle), C.int(fd)); code != 0 {
		return fmt.Errorf("调试连接网络绑定失败: %w", syscall.Errno(code))
	}
	return nil
}

type routedTransport struct {
	*http.Transport
	route string
}

func usedRoute(c *http.Client) string {
	if t, ok := c.Transport.(*routedTransport); ok {
		return t.route
	}
	return "embedded-tsnet"
}

var startDone chan struct{}
var startError string
var noticeGuard sync.Mutex
var lastNotice string
var lastLoginAttempt time.Time
var secretURL = regexp.MustCompile(`https?://[^\s]+`)

func remember(format string, args ...any) {
	message := secretURL.ReplaceAllString(fmt.Sprintf(format, args...), "[URL]")
	if strings.Contains(strings.ToLower(message), "error") || strings.Contains(strings.ToLower(message), "fail") {
		noticeGuard.Lock()
		lastNotice = message
		noticeGuard.Unlock()
	}
}

func reply(v any) *C.char       { b, _ := json.Marshal(v); return C.CString(string(b)) }
func failure(err error) *C.char { return reply(map[string]any{"error": err.Error()}) }

//export thor_link_start
func thor_link_start(directory, host, interfacesJSON *C.char, network C.ulonglong) *C.char {
	guard.Lock()
	defer guard.Unlock()
	var entries []struct {
		Name      string
		Index     int
		MTU       int
		Loopback  bool
		Addresses []string
	}
	if err := json.Unmarshal([]byte(C.GoString(interfacesJSON)), &entries); err != nil {
		return failure(err)
	}
	snapshot := C.GoString(interfacesJSON)
	changed := underlayNetwork.Swap(uint64(network)) != uint64(network) || interfaceSnapshot != snapshot
	interfaceSnapshot = snapshot
	androidInterfaces = nil
	for _, e := range entries {
		flags := net.FlagUp | net.FlagRunning
		if e.Loopback {
			flags |= net.FlagLoopback
		}
		var addresses []net.Addr
		for _, s := range e.Addresses {
			p, err := netip.ParsePrefix(s)
			if err == nil {
				addresses = append(addresses, &net.IPNet{IP: net.IP(p.Addr().AsSlice()), Mask: net.CIDRMask(p.Bits(), p.Addr().BitLen())})
			}
		}
		androidInterfaces = append(androidInterfaces, netmon.Interface{Interface: &net.Interface{Index: e.Index, MTU: e.MTU, Name: e.Name, Flags: flags}, AltAddrs: addresses})
	}
	if node != nil {
		if changed {
			n, done := node, startDone
			go func() {
				<-done
				if sys := n.Sys(); sys != nil {
					if monitor, ok := sys.NetMon.GetOK(); ok {
						monitor.InjectEvent()
					}
				}
			}()
		}
		return reply(map[string]bool{"started": true})
	}
	netns.SetAndroidBindToNetworkFunc(bindUnderlay)
	netmon.RegisterInterfaceGetter(func() ([]netmon.Interface, error) {
		guard.Lock()
		defer guard.Unlock()
		return append([]netmon.Interface(nil), androidInterfaces...), nil
	})
	// Android's app UID cannot use netlink. Java supplies the actual interface list.
	os.Setenv("SSL_CERT_DIR", "/apex/com.android.conscrypt/cacerts:/system/etc/security/cacerts")
	os.Setenv("TS_NO_LOGS_NO_SUPPORT", "true")
	path := C.GoString(directory)
	if err := os.MkdirAll(path, 0700); err != nil {
		return failure(err)
	}
	for _, directory := range []string{"logs", "cache", "tmp"} {
		if err := os.MkdirAll(filepath.Join(path, directory), 0700); err != nil {
			return failure(err)
		}
	}
	os.Setenv("TS_LOGS_DIR", filepath.Join(path, "logs"))
	os.Setenv("XDG_CACHE_HOME", filepath.Join(path, "cache"))
	os.Setenv("TMPDIR", filepath.Join(path, "tmp"))
	n := &tsnet.Server{Dir: path, Hostname: C.GoString(host), Logf: remember, UserLogf: remember}
	node = n
	startDone = make(chan struct{})
	// Start must not hold guard: the interface callback also needs it.
	go func() {
		defer func() {
			if value := recover(); value != nil {
				guard.Lock()
				startError = fmt.Sprint(value)
				guard.Unlock()
			}
			close(startDone)
		}()
		if err := n.Start(); err != nil {
			guard.Lock()
			startError = err.Error()
			guard.Unlock()
		}
	}()
	return reply(map[string]bool{"started": true})
}

//export thor_link_status
func thor_link_status() *C.char {
	guard.Lock()
	n := node
	done := startDone
	guard.Unlock()
	if n == nil {
		return reply(map[string]string{"state": "Stopped"})
	}
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		return reply(map[string]string{"state": "Starting"})
	}
	guard.Lock()
	errText := startError
	guard.Unlock()
	if errText != "" {
		return failure(fmt.Errorf("内置连接启动失败：%s", errText))
	}
	lc, err := n.LocalClient()
	if err != nil {
		return failure(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	st, err := lc.StatusWithoutPeers(ctx)
	if err != nil {
		return failure(err)
	}
	// Start can observe NoState before the backend enters NeedsLogin. In that
	// case tsnet's initial interactive-login check has already passed by.
	if st.BackendState == "NeedsLogin" && st.AuthURL == "" {
		noticeGuard.Lock()
		begin := time.Since(lastLoginAttempt) > 30*time.Second
		if begin {
			lastLoginAttempt = time.Now()
		}
		noticeGuard.Unlock()
		if begin {
			go func() {
				loginCtx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
				defer cancel()
				if err := lc.StartLoginInteractive(loginCtx); err != nil {
					remember("interactive login failed: %v", err)
				}
			}()
		}
	}
	noticeGuard.Lock()
	notice := lastNotice
	noticeGuard.Unlock()
	return reply(map[string]any{"state": st.BackendState, "auth_url": st.AuthURL, "ips": st.TailscaleIPs, "health": st.Health, "last_error": notice})
}

func clientFor(rawURL string) (*http.Client, error) {
	u, err := url.Parse(rawURL)
	if err != nil {
		return nil, err
	}
	if u.Scheme != "https" || u.User != nil || u.Hostname() == "" {
		return nil, fmt.Errorf("HTTPS required")
	}
	guard.Lock()
	n := node
	allowed := peerHost
	lan := lanAddress
	guard.Unlock()
	if n == nil {
		return nil, fmt.Errorf("内置连接尚未启动")
	}
	if allowed == "" || u.Host != allowed {
		return nil, fmt.Errorf("电脑地址不匹配")
	}
	c := n.HTTPClient()
	c.Timeout = 30 * time.Second
	{
		transport := &routedTransport{Transport: &http.Transport{TLSHandshakeTimeout: 8 * time.Second, ResponseHeaderTimeout: 25 * time.Second, MaxIdleConns: 4, IdleConnTimeout: 30 * time.Second}}
		transport.DialTLSContext = func(ctx context.Context, network, address string) (net.Conn, error) {
			config := &tls.Config{ServerName: u.Hostname(), MinVersion: tls.VersionTLS12, NextProtos: []string{"http/1.1"}}
			if lan != "" {
				quick, cancel := context.WithTimeout(ctx, 2*time.Second)
				direct := tls.Dialer{NetDialer: &net.Dialer{}, Config: config}
				connection, err := direct.DialContext(quick, "tcp", lan)
				cancel()
				if err == nil {
					transport.route = "lan-https"
					return connection, nil
				}
			}
			lc, localErr := n.LocalClient()
			if localErr != nil {
				return nil, localErr
			}
			st, statusErr := lc.Status(ctx)
			if statusErr != nil {
				return nil, statusErr
			}
			if st.BackendState != "Running" {
				return nil, fmt.Errorf("局域网电脑暂时不可达，内置私网尚未连接")
			}
			// Resolve only against the authenticated peer map. Android's system
			// resolver cannot resolve tailnet names, and tsnet's MagicDNS index
			// can lag behind the already available local-API peer status.
			host, port, splitErr := net.SplitHostPort(address)
			if splitErr != nil {
				return nil, splitErr
			}
			target := address
			if _, parseErr := netip.ParseAddr(host); parseErr != nil {
				target = ""
				for _, peer := range st.Peer {
					if !strings.EqualFold(strings.TrimSuffix(peer.DNSName, "."), strings.TrimSuffix(host, ".")) {
						continue
					}
					for _, ip := range peer.TailscaleIPs {
						if ip.Is4() {
							target = net.JoinHostPort(ip.String(), port)
							break
						}
					}
					if target != "" {
						break
					}
				}
				if target == "" {
					return nil, fmt.Errorf("内置私网尚未找到已配对的电脑，请稍后重试")
				}
			}
			raw, dialErr := n.Dial(ctx, network, target)
			if dialErr != nil {
				return nil, dialErr
			}
			secure := tls.Client(raw, config)
			if err := secure.HandshakeContext(ctx); err != nil {
				raw.Close()
				return nil, err
			}
			transport.route = "embedded-tsnet"
			return secure, nil
		}
		c.Transport = transport
	}
	c.CheckRedirect = func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }
	return c, nil
}

//export thor_link_peer
func thor_link_peer(input *C.char) *C.char {
	var route struct {
		Host string `json:"host"`
		LAN  string `json:"lan"`
	}
	if err := json.Unmarshal([]byte(C.GoString(input)), &route); err != nil {
		return failure(err)
	}
	if route.LAN != "" {
		host, port, err := net.SplitHostPort(route.LAN)
		if err != nil {
			return failure(fmt.Errorf("局域网地址格式应为 IP:端口"))
		}
		ip, err := netip.ParseAddr(host)
		number, numErr := strconv.Atoi(port)
		if err != nil || !ip.Is4() || !ip.IsPrivate() || numErr != nil || number < 1 || number > 65535 {
			return failure(fmt.Errorf("备用通道只允许局域网 IPv4 地址"))
		}
	}
	guard.Lock()
	peerHost = route.Host
	lanAddress = route.LAN
	guard.Unlock()
	return reply(map[string]bool{"ok": true})
}

//export thor_link_request
func thor_link_request(method, address, body, cookie *C.char) *C.char {
	raw := C.GoString(address)
	c, err := clientFor(raw)
	if err != nil {
		return failure(err)
	}
	req, err := http.NewRequest(C.GoString(method), raw, strings.NewReader(C.GoString(body)))
	if err != nil {
		return failure(err)
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Thor-Client", "mobile")
	if v := C.GoString(cookie); v != "" {
		req.Header.Set("Cookie", v)
	}
	res, err := c.Do(req)
	if err != nil {
		return failure(err)
	}
	defer res.Body.Close()
	data, err := io.ReadAll(io.LimitReader(res.Body, 32*1024*1024+1))
	if err != nil {
		return failure(err)
	}
	if len(data) > 32*1024*1024 {
		return failure(fmt.Errorf("响应过大"))
	}
	return reply(map[string]any{"status": res.StatusCode, "body": base64.StdEncoding.EncodeToString(data), "cookie": res.Header.Get("Set-Cookie"), "route": usedRoute(c)})
}

//export thor_link_download
func thor_link_download(address, cookie, filename, digest *C.char) *C.char {
	raw := C.GoString(address)
	c, err := clientFor(raw)
	if err != nil {
		return failure(err)
	}
	c.Timeout = 10 * time.Minute
	req, err := http.NewRequest("GET", raw, nil)
	if err != nil {
		return failure(err)
	}
	req.Header.Set("Cookie", C.GoString(cookie))
	res, err := c.Do(req)
	if err != nil {
		return failure(err)
	}
	defer res.Body.Close()
	if res.StatusCode != 200 {
		return failure(fmt.Errorf("下载失败 HTTP %d", res.StatusCode))
	}
	path := C.GoString(filename)
	temp := path + ".download"
	f, err := os.OpenFile(temp, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0600)
	if err != nil {
		return failure(err)
	}
	hash := sha256.New()
	size, err := io.Copy(io.MultiWriter(f, hash), io.LimitReader(res.Body, 512*1024*1024+1))
	closeErr := f.Close()
	if err != nil || closeErr != nil || size > 512*1024*1024 {
		os.Remove(temp)
		return failure(fmt.Errorf("下载未完成或超过限制"))
	}
	actual := hex.EncodeToString(hash.Sum(nil))
	if actual != C.GoString(digest) {
		os.Remove(temp)
		return failure(fmt.Errorf("APK SHA256 不一致"))
	}
	if err := os.Rename(temp, path); err != nil {
		return failure(err)
	}
	return reply(map[string]any{"sha256": actual, "bytes": size, "route": usedRoute(c)})
}

//export thor_link_free
func thor_link_free(p *C.char) { C.free(unsafe.Pointer(p)) }

func main() {}

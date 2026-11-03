package local.thor.bilichannels;

import java.util.Arrays;
import java.util.List;

/** Four persistent handheld cells. Queue movement changes occupants, not retained positions. */
public final class PreviewSeats {
    private final String[] occupants = new String[4];

    public void clear() { Arrays.fill(occupants, null); }

    public int[] arrange(List<Video> queue, int selected) {
        int[] seats = {-1, -1, -1, -1};
        for (int seat = 0; seat < seats.length; seat++) {
            for (int slot = 0; slot < Math.min(6, queue.size()); slot++) {
                Video video = queue.get(slot);
                if (!video.isPortrait() && video.tileKey().equals(occupants[seat])) {
                    seats[seat] = slot;
                    break;
                }
            }
        }
        // Fill vacancies in queue order, retaining every occupant still in this window.
        for (int slot = 0; slot < Math.min(6, queue.size()); slot++) {
            if (queue.get(slot).isPortrait() || contains(seats, slot)) continue;
            for (int seat = 0; seat < seats.length; seat++) if (seats[seat] < 0) {
                seats[seat] = slot;
                break;
            }
        }
        // A selected standby video replaces the furthest visible queue neighbour.
        if (selected >= 0 && selected < Math.min(6, queue.size())
                && !queue.get(selected).isPortrait() && !contains(seats, selected)) {
            int replace = 0;
            for (int seat = 1; seat < seats.length; seat++)
                if (Math.abs(seats[seat] - selected) > Math.abs(seats[replace] - selected)) replace = seat;
            seats[replace] = selected;
        }
        for (int seat = 0; seat < seats.length; seat++)
            occupants[seat] = seats[seat] < 0 ? null : queue.get(seats[seat]).tileKey();
        return seats;
    }

    private boolean contains(int[] seats, int slot) {
        for (int value : seats) if (value == slot) return true;
        return false;
    }
}

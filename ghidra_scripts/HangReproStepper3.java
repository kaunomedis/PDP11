//Third isolated reproduction - same exact register snapshot, this time with
//the REAL, complete VT52-decoding processWrite/processRead logic (copied
//verbatim from InterractiveVT52.java) attached, including redraw().
//Uses a headless JTextArea (never shown) so redraw() has something to call
//setText() on without needing a real visible window.
//@author 
//@category PDP11 Hardware
//@keybinding 
//@menupath 
//@toolbar 
//@runtime Java
import ghidra.app.script.GhidraScript;
import ghidra.app.emulator.EmulatorHelper;
import ghidra.app.emulator.MemoryAccessFilter;
import ghidra.program.model.address.Address;
import ghidra.program.model.address.AddressSpace;
import ghidra.util.task.TaskMonitor;
import javax.swing.*;

public class HangReproStepper3 extends GhidraScript {

    private static final int ROWS = 24;
    private static final int COLS = 80;
    private static final char NUL_MARKER = '\u2588';

    public void run() throws Exception {
        EmulatorHelper emuHelper = new EmulatorHelper(currentProgram);

        emuHelper.writeRegister("PC", 0x19b8);
        emuHelper.writeRegister("R0", 0x47);
        emuHelper.writeRegister("R1", 0x1);
        emuHelper.writeRegister("R2", 0x0);
        emuHelper.writeRegister("R3", 0x0);
        emuHelper.writeRegister("R4", 0x0);
        emuHelper.writeRegister("R5", 0x1921);
        emuHelper.writeRegister("SP", 0x1fc);
        emuHelper.writeRegister("PS", 0xc);

        Address xbufAddr = toAddr(0xFF76);
        Address rbufAddr = toAddr(0xFF72);
        Address rcsrAddr = toAddr(0xFF70);
        Address xcsrAddr = toAddr(0xFF74);

        emuHelper.writeMemoryValue(xcsrAddr, 2, 0x0080);
        emuHelper.writeMemoryValue(rcsrAddr, 2, 0x0000);

        JTextArea screen = new JTextArea(ROWS, COLS); // never shown - headless
        JLabel rcsrCounterLabel = new JLabel(); // never shown - headless, just needs to exist

        final boolean[] escOn   = { false };
        final boolean[] escY0   = { false };
        final boolean[] escY1   = { false };
        final boolean[] mode15IE= { false };
        final boolean[] grfMode = { false };
        final boolean[] langRus = { false };
        final int[] escY0L = { 0 };

        final char[][] grid = new char[ROWS][COLS];
        for (char[] row : grid) java.util.Arrays.fill(row, ' ');
        final int[] cur = { 0, 0 };
        final long[] rcsrReadCount = { 0 };

        emuHelper.getEmulator().addMemoryAccessFilter(new MemoryAccessFilter() {

            private static final String KOI7_CYRIL  = "\u042e\u0410\u0411\u0426\u0414\u0415\u0424\u0413\u0425\u0418\u0419\u041a\u041b\u041c\u041d\u041e\u041f\u042f\u0420\u0421\u0422\u0423\u0416\u0412\u042c\u042b\u0417\u0428\u042d\u0429\u0427";
            // Order: Ю А Б Ц Д Е Ф Г Х И Й К Л М Н О П Я Р С Т У Ж В Ь Ы З Ш Э Щ Ч

            private volatile long lastRedrawTime = 0;
            private static final long REDRAW_INTERVAL_MS = 20;

            private void redraw() {
                long now = System.currentTimeMillis();
                if (now - lastRedrawTime < REDRAW_INTERVAL_MS) return;
                lastRedrawTime = now;
                StringBuilder sb = new StringBuilder();
                for (int r = 0; r < ROWS; r++) {
                    sb.append(grid[r]);
                    if (r < ROWS - 1) sb.append('\n');
                }
                String text = sb.toString();
                SwingUtilities.invokeLater(() -> screen.setText(text));
            }

            private void clampCursor() {
                if (cur[0] < 0) cur[0] = 0;
                if (cur[0] >= ROWS) cur[0] = ROWS - 1;
                if (cur[1] < 0) cur[1] = 0;
                if (cur[1] >= COLS) cur[1] = COLS - 1;
            }

            private void scrollUp() {
                for (int r = 0; r < ROWS - 1; r++) {
                    System.arraycopy(grid[r + 1], 0, grid[r], 0, COLS);
                }
                java.util.Arrays.fill(grid[ROWS - 1], ' ');
            }

            private void putRawChar(char c) {
                grid[cur[0]][cur[1]] = c;
                cur[1]++;
                if (cur[1] >= COLS) {
                    cur[1] = 0;
                    cur[0]++;
                    if (cur[0] >= ROWS) { scrollUp(); cur[0] = ROWS - 1; }
                }
            }

            private void putEscapeAtomic(String s) {
                if (cur[1] + s.length() > COLS) {
                    cur[1] = 0;
                    cur[0]++;
                    if (cur[0] >= ROWS) { scrollUp(); cur[0] = ROWS - 1; }
                }
                for (char c : s.toCharArray()) {
                    grid[cur[0]][cur[1]] = c;
                    cur[1]++;
                }
            }

            private void home() { cur[0] = 0; cur[1] = 0; }
            private void bline() { cur[1] = 0; }

            private void lfeed() {
                cur[0]++;
                if (cur[0] >= ROWS) { scrollUp(); cur[0] = ROWS - 1; }
            }

            private void revlf() {
                cur[0]--;
                if (cur[0] < 0) { scrollUp(); cur[0] = 0; }
            }

            private void cup()    { if (cur[0] > 0) cur[0]--; }
            private void cdown()  { if (cur[0] < ROWS - 1) cur[0]++; }
            private void cleft()  { if (cur[1] > 0) cur[1]--; }
            private void cright() { if (cur[1] < COLS - 1) cur[1]++; }

            private void htab() {
                if (cur[1] >= COLS - 1) return;
                if (cur[1] >= 72) { cur[1]++; }
                else { cur[1] = 72; }
                clampCursor();
            }

            private void clreol() {
                for (int x = cur[1]; x < COLS; x++) grid[cur[0]][x] = ' ';
            }

            private void clreos() {
                clreol();
                for (int r = cur[0] + 1; r < ROWS; r++) java.util.Arrays.fill(grid[r], ' ');
            }

            private void clscreen() {
                for (char[] row : grid) java.util.Arrays.fill(row, ' ');
                home();
            }

            private void scrlup() { scrollUp(); }

            private void insblank() {
                for (int x = COLS - 1; x > cur[1]; x--) grid[cur[0]][x] = grid[cur[0]][x - 1];
                grid[cur[0]][cur[1]] = ' ';
            }

            private void delchar() {
                for (int x = cur[1]; x < COLS - 1; x++) grid[cur[0]][x] = grid[cur[0]][x + 1];
                grid[cur[0]][COLS - 1] = ' ';
            }

            private void curmove(int row, int col) {
                if (row < 0) row = 0;
                if (row >= ROWS) row = ROWS - 1;
                if (col < 0) col = 0;
                if (col >= COLS) col = COLS - 1;
                cur[0] = row;
                cur[1] = col;
            }

            private void identify() {
                try {
                    for (int c : new int[]{0x1B, '/', 'L'}) {
                        emuHelper.writeMemoryValue(rbufAddr, 2, c);
                        emuHelper.writeMemoryValue(rcsrAddr, 2, 0x80);
                    }
                } catch (Exception e) { /* best effort */ }
            }

            private int koi7Translate(int r0) {
                // Real KOI-7 N1: codes 0140-0176 octal get replaced "by sound"
                // with uppercase Cyrillic letters.
                if (r0 >= 0140 && r0 <= 0176) {
                    int idx = r0 - 0140;
                    if (idx < KOI7_CYRIL.length()) return KOI7_CYRIL.charAt(idx);
                }
                return r0;
            }

            private void putCharProcessed(int r0) {
                if (grfMode[0] && r0 >= 0137 && r0 <= 0176) {
                    r0 += 041;
                } else if (langRus[0]) {
                    r0 = koi7Translate(r0);
                }
                if (r0 == 0x00) {
                    putEscapeAtomic(String.valueOf(NUL_MARKER));
                } else if ((r0 >= 0x20 && r0 <= 0x7E) || (r0 >= 0x0400 && r0 <= 0x04FF)) {
                    // printable ASCII OR a real Cyrillic Unicode codepoint from KOI-7 translation
                    putRawChar((char) r0);
                } else {
                    putEscapeAtomic(String.format("[%02X]", r0));
                }
            }

            private void escProcess(int r0) {
                if (escY1[0]) {
                    int col = r0 - 32;
                    curmove(escY0L[0], col);
                    escY1[0] = false;
                    escOn[0] = false;
                    return;
                }
                if (escY0[0]) {
                    escY0L[0] = r0 - 32;
                    escY0[0] = false;
                    escY1[0] = true;
                    return;
                }
                switch (r0) {
                    case 'B': cdown(); break;
                    case 'A': cup(); break;
                    case 'C': cright(); break;
                    case 'D': cleft(); break;
                    case 'H': home(); break;
                    case 'Y': escY0[0] = true; return;
                    case 'J': clreos(); break;
                    case 075: break;
                    case 076: break;
                    case 'K': clreol(); break;
                    case 0111: revlf(); break;
                    case 'Z': identify(); break;
                    case 'E': mode15IE[0] = true; break;
                    case 'F': grfMode[0] = true; break;
                    case 'G': grfMode[0] = false; break;
                    case 0133: break;
                    case 0134: break;
                    default: break;
                }
                escOn[0] = false;
            }

            private boolean ch15ie(int r0) {
                switch (r0) {
                    case 010: home(); return true;
                    case 013: clreol(); return true;
                    case 014: clscreen(); return true;
                    case 022: case 026: scrlup(); return true;
                    case 023: insblank(); return true;
                    case 024: delchar(); return true;
                    case 025:
                        bline();
                        cur[0]++;
                        if (cur[0] >= ROWS) { scrollUp(); cur[0] = ROWS - 1; }
                        return true;
                    case 027: mode15IE[0] = false; return true;
                    case 031: cright(); return true;
                    case 032: cleft(); return true;
                    case 034: cup(); return true;
                    case 035: cdown(); return true;
                    default: return false;
                }
            }

            @Override
            protected void processRead(AddressSpace space, long offset, int size, byte[] values) {
                Address read = space.getAddress(offset);
                if (read.equals(rbufAddr)) {
                    try {
                        byte[] c = emuHelper.readMemory(rcsrAddr, 2);
                        int val = (c[0] & 0xFF) | ((c[1] & 0xFF) << 8);
                        emuHelper.writeMemoryValue(rcsrAddr, 2, val & ~0x80);
                    } catch (Exception e) {
                        // best effort only
                    }
                } else if (read.equals(rcsrAddr)) {
                    rcsrReadCount[0]++;
                    int rcsrVal = (values[0] & 0xFF) | (size > 1 ? ((values[1] & 0xFF) << 8) : 0);
                    long count = rcsrReadCount[0];
                    SwingUtilities.invokeLater(() ->
                        rcsrCounterLabel.setText(String.format("RCSR reads: %d (last value: 0x%04X)", count, rcsrVal)));
                } else if (read.equals(xcsrAddr)) {
                    // Real hardware: XCSR bit7 becomes 1 again once XBUF is ready for the
                    // next character. We have no real transmission-time delay to model, so
                    // "ready again by the time the program next checks" is our stand-in -
                    // guarantees the poll loop always sees busy-then-ready, deterministically,
                    // regardless of wall-clock/redraw timing.
                    try {
                        byte[] c = emuHelper.readMemory(xcsrAddr, 2);
                        int val = (c[0] & 0xFF) | ((c[1] & 0xFF) << 8);
                        emuHelper.writeMemoryValue(xcsrAddr, 2, val | 0x80);
                    } catch (Exception e) {
                        // best effort only
                    }
                }
            }

            @Override
            protected void processWrite(AddressSpace space, long offset, int size, byte[] values) {
                Address written = space.getAddress(offset);
                if (!written.equals(xbufAddr)) return;

                // Real hardware: "After an Output cycle to XBUF, this bit must be cleared
                // to 0 by the hardware." Do this before anything else.
                try {
                    emuHelper.writeMemoryValue(xcsrAddr, 2, 0x0000);
                } catch (Exception e) {
                    // best effort only
                }

                int r0 = values[0] & 0x7F;

                if (escOn[0]) {
                    escProcess(r0);
                    redraw();
                    return;
                }

                if (r0 == 0x0D) { bline(); redraw(); return; }
                if (r0 == 0x0A) { lfeed(); redraw(); return; }
                if (r0 == 0x0F) { langRus[0] = false; return; }
                if (r0 == 0x0E) { langRus[0] = true; return; }
                if (r0 == 0x07) { return; }
                if (r0 == 0x7F) { return; }

                if (mode15IE[0]) {
                    if (ch15ie(r0)) { redraw(); return; }
                } else {
                    if (r0 == 0x08) { cleft(); redraw(); return; }
                    if (r0 == 0x09) { htab(); redraw(); return; }
                    if (r0 == 0x0B || r0 == 0x0C) { lfeed(); redraw(); return; }
                    if (r0 == 0x05) { identify(); return; }
                    if (r0 == 0x1B) { escOn[0] = true; return; }
                }

                putCharProcessed(r0);
                redraw();
            }
        });

        println("Before step: PC=" + Long.toHexString(emuHelper.readRegister("PC").longValue()));

        long timeoutMs = 5000;
        Thread stepThread = new Thread(() -> {
            try {
                emuHelper.step(monitor);
            } catch (Exception e) {
                println("step() threw: " + e);
            }
        });
        stepThread.start();
        stepThread.join(timeoutMs);

        if (stepThread.isAlive()) {
            println("CONFIRMED: step() with FULL VT52 processWrite/redraw logic did NOT return within " + timeoutMs + "ms.");
            monitor.cancel();
            stepThread.interrupt();
        } else {
            println("step() returned normally.");
            println("After step: PC=" + Long.toHexString(emuHelper.readRegister("PC").longValue()));
        }

        emuHelper.dispose();
    }
}

//Line Interractive VT52 in emulator, with separate output window
//Decoder logic ported directly from confirmed real firmware:
//https://github.com/forth32/vt52/blob/main/vt52-firmware/terminal.mac
//(CHCONTROL / ESCPROCESS routines - both VT52 and 15IE command sets)
//KOI-7 Cyrillic table from https://www.kermitproject.org/koi7.html
//@author Levas
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
import ghidra.program.model.listing.Instruction;
import ghidra.util.task.TaskMonitor;
import javax.swing.*;
import java.awt.*;

public class InterractiveVT52 extends GhidraScript {

    private static final int ROWS = 24;
    private static final int COLS = 80;
    private static final char NUL_MARKER = '\u2588';

    private volatile boolean windowOpen = true;

    public void run() throws Exception {
        EmulatorHelper emuHelper = new EmulatorHelper(currentProgram);
        String startPCStr = askString("Start PC", "Enter starting PC (hex, no 0x):", "0080");
        long startPC = Long.parseLong(startPCStr.trim(), 16);

        String startSPStr = askString("Start SP", "Enter starting stack SP (hex, no 0x):", "5000");
        long startSP = Long.parseLong(startSPStr.trim(), 16);

        emuHelper.writeRegister("PC", startPC);
        emuHelper.writeRegister("PS", 0x0000);
        emuHelper.writeRegister("SP", startSP);

        JFrame frame = new JFrame("PDP-11 VT52/15IE Console (live, rendered)");
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setAlwaysOnTop(true);
        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            public void windowClosed(java.awt.event.WindowEvent e) {
                windowOpen = false;
            }
        });

        JTextArea screen = new JTextArea(ROWS, COLS);
        screen.setEditable(false);
        screen.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
        JScrollPane screenScroll = new JScrollPane(screen);
        JLabel status = new JLabel("Starting...");

        JPanel content = new JPanel(new BorderLayout());
        content.add(new JLabel("PDP-11 VT52/15IE Rendered Screen", SwingConstants.CENTER), BorderLayout.NORTH);
        content.add(screenScroll, BorderLayout.CENTER);
        content.add(status, BorderLayout.SOUTH);
        frame.setContentPane(content);
        frame.pack();
        frame.setMinimumSize(new Dimension(790, 550));
        frame.setLocationRelativeTo(null);
        SwingUtilities.invokeLater(() -> frame.setVisible(true));

        emuHelper.writeMemoryValue(toAddr(0xFF74), 2, 0x0080); // XCSR: start ready (bit7 set)

        Address xbufAddr = toAddr(0xFF76);
        Address rbufAddr = toAddr(0xFF72);
        Address rcsrAddr = toAddr(0xFF70);
        Address xcsrAddr = toAddr(0xFF74);

        emuHelper.writeMemoryValue(rcsrAddr, 2, 0x0000);

        final StringBuilder pending = new StringBuilder();

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

        while (windowOpen && !monitor.isCancelled()) {
            long pc = emuHelper.readRegister("PC").longValue();
            Instruction instr = currentProgram.getListing().getInstructionAt(toAddr(pc));
            String instrText = (instr != null) ? instr.toString() : "??";
            if (instrText.trim().startsWith("HALT")) {
                String haltMsg = "Reached HALT at PC=0x" + Long.toHexString(pc) + " - stopping.";
                SwingUtilities.invokeLater(() -> status.setText(haltMsg));
                break;
            }

            byte[] rcsrBytes = emuHelper.readMemory(rcsrAddr, 2);
            int rcsrVal = (rcsrBytes[0] & 0xFF) | ((rcsrBytes[1] & 0xFF) << 8);
            boolean ready = (rcsrVal & 0x80) != 0;

            if (!ready) {
                if (pending.length() == 0) {

                    // Flush the screen NOW, unconditionally, bypassing the redraw
                    // throttle - guarantees the input prompt always reflects the
                    // real, current state, never a stale one.
                    StringBuilder flushSb = new StringBuilder();
                    for (int r = 0; r < ROWS; r++) {
                        flushSb.append(grid[r]);
                        if (r < ROWS - 1) flushSb.append('\n');
                    }
                    String flushText = flushSb.toString();
                    SwingUtilities.invokeLater(() -> screen.setText(flushText));

                    String more = askString("Console Input @ PC=0x" + Long.toHexString(pc),
                        "Type text to send (use ^O, ^L etc for control chars; type 'stop' to stop sending):", "stop");
                    if (more != null && !more.equalsIgnoreCase("stop")) {
                        StringBuilder decoded = new StringBuilder();
                        for (int i = 0; i < more.length(); i++) {
                            char ch = more.charAt(i);
                            if (ch == '^' && i + 1 < more.length()) {
                                char next = Character.toUpperCase(more.charAt(i + 1));
                                decoded.append((char) (next & 0x1F));
                                i++;
                            } else {
                                decoded.append(ch);
                            }
                        }
                        pending.append(decoded).append("\r");
                    }
                }
                if (pending.length() > 0) {
                    char c = pending.charAt(0);
                    pending.deleteCharAt(0);
                    emuHelper.writeMemoryValue(rbufAddr, 2, (long) c & 0xFF);
                    emuHelper.writeMemoryValue(rcsrAddr, 2, 0x80);
                }
            }

            emuHelper.step(monitor);
        }
        StringBuilder finalSb = new StringBuilder();
        for (int r = 0; r < ROWS; r++) {
            finalSb.append(grid[r]);
            if (r < ROWS - 1) finalSb.append('\n');
        }
        String finalText = finalSb.toString();
        SwingUtilities.invokeLater(() -> screen.setText(finalText));
	String pc = " PC="+Long.toHexString(emuHelper.readRegister("PC").longValue());
	String r0 = " R0="+Long.toHexString(emuHelper.readRegister("R0").longValue());
	String r1 = " R1="+Long.toHexString(emuHelper.readRegister("R1").longValue());
	String r2 = " R2="+Long.toHexString(emuHelper.readRegister("R2").longValue());
	String r3 = " R3="+Long.toHexString(emuHelper.readRegister("R3").longValue());
	String r4 = " R4="+Long.toHexString(emuHelper.readRegister("R4").longValue());
	String r5 = " R5="+Long.toHexString(emuHelper.readRegister("R5").longValue());
	String sp = " SP="+Long.toHexString(emuHelper.readRegister("SP").longValue());
	String ps = " PS="+Long.toHexString(emuHelper.readRegister("PS").longValue());
	
	// FF70,FF72,FF74,FF76	
	byte[] b = emuHelper.readMemory(toAddr(0xFF70), 8);
	String ramas=" RAM: ";
	for(int i=0;i<8;i=i+2) {
    int val = (b[i+0] & 0xFF) | ((b[i+1] & 0xFF) << 8);
    ramas=ramas+String.format("[%04X]=%04X ", 0xFF70+i, val);
	}

	print ("Stopped @" +pc+r0+r1+r2+r3+r4+r5+sp+ps+ramas+"\n\r");
        emuHelper.dispose();
    }
}

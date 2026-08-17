//Line Interractive VT52 in emulator, with separate output window
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

    private static final int ST_NORMAL = 0;
    private static final int ST_ESC = 1;
    private static final int ST_ESC_Y_ROW = 2;
    private static final int ST_ESC_Y_COL = 3;
    private static final int ST_ESC_E_DATA = 4;

    private static final int ROWS = 24;
    private static final int COLS = 80;
    private static final char NUL_MARKER = '\u2588'; // solid block - makes 0x00 bytes visually obvious

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

        JFrame frame = new JFrame("PDP-11 VT52 Console (live, rendered)");
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
        content.add(new JLabel("PDP-11 VT52 Rendered Screen", SwingConstants.CENTER), BorderLayout.NORTH);
        content.add(screenScroll, BorderLayout.CENTER);
        content.add(status, BorderLayout.SOUTH);
        frame.setContentPane(content);
        frame.pack();
        frame.setMinimumSize(new Dimension(750, 500));
        frame.setLocationRelativeTo(null);
        SwingUtilities.invokeLater(() -> frame.setVisible(true));

        emuHelper.writeMemoryValue(toAddr(0xFF74), 2, 0xFFFF);

        Address xbufAddr = toAddr(0xFF76);
        Address rbufAddr = toAddr(0xFF72);
        Address rcsrAddr = toAddr(0xFF70);

        emuHelper.writeMemoryValue(rcsrAddr, 2, 0x0000);

        final StringBuilder pending = new StringBuilder();
        final int[] escState = { ST_NORMAL };
        final int[] escRow = { 0 };
        final int[] escECount = { 0 };
        final boolean[] graphicsMode = { false };

        // --- The actual screen grid, and cursor position, that VT52 commands operate on ---
        final char[][] grid = new char[ROWS][COLS];
        for (char[] row : grid) java.util.Arrays.fill(row, ' ');
        final int[] cur = { 0, 0 }; // cur[0]=row, cur[1]=col

        emuHelper.getEmulator().addMemoryAccessFilter(new MemoryAccessFilter() {

			private volatile long lastRedrawTime = 0;
            private static final long REDRAW_INTERVAL_MS = 50; // ~20 updates/sec max

            private void redraw() {
                long now = System.currentTimeMillis();
                if (now - lastRedrawTime < REDRAW_INTERVAL_MS) {
                    return; // grid is already updated; just skip the expensive Swing push for now
                }
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

            private void putChar(char c) {
                grid[cur[0]][cur[1]] = c;
                cur[1]++;
                if (cur[1] >= COLS) {
                    cur[1] = 0;
                    cur[0]++;
                    if (cur[0] >= ROWS) {
                        scrollUp();
                        cur[0] = ROWS - 1;
                    }
                }
            }

            private void clearScreen() {
                for (char[] row : grid) java.util.Arrays.fill(row, ' ');
                cur[0] = 0;
                cur[1] = 0;
            }

            private void eraseToEndOfScreen() {
                for (int x = cur[1]; x < COLS; x++) grid[cur[0]][x] = ' ';
                for (int r = cur[0] + 1; r < ROWS; r++) java.util.Arrays.fill(grid[r], ' ');
            }

            private void eraseToEndOfLine() {
                for (int x = cur[1]; x < COLS; x++) grid[cur[0]][x] = ' ';
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
                }
            }

            @Override
            protected void processWrite(AddressSpace space, long offset, int size, byte[] values) {
                Address written = space.getAddress(offset);
                if (!written.equals(xbufAddr)) {
                    return;
                }
                int b = values[0] & 0xFF;

                switch (escState[0]) {
                    case ST_ESC:
                        switch (b) {
                            case 'A': cur[0]--; clampCursor(); break;
                            case 'B': cur[0]++; clampCursor(); break;
                            case 'C': cur[1]++; clampCursor(); break;
                            case 'D': cur[1]--; clampCursor(); break;
                            case 'E':
                                // Confirmed via real disassembly (FUN_195e): ESC E consumes
                                // exactly 8 following bytes, each sent to XBUF as an ordinary
                                // printable character - NOT a clear-screen, NOT a bitmap.
                                escState[0] = ST_ESC_E_DATA;
                                escECount[0] = 0;
                                return;
                            case 'F': graphicsMode[0] = true; break;
                            case 'G': graphicsMode[0] = false; break;
                            case 'H': cur[0] = 0; cur[1] = 0; break;
                            case 'I':
                                cur[0]--;
                                if (cur[0] < 0) { scrollUp(); cur[0] = 0; }
                                break;
                            case 'J': eraseToEndOfScreen(); break;
                            case 'K': eraseToEndOfLine(); break;
                            case 'Z':
                                try {
                                    emuHelper.writeMemoryValue(rbufAddr, 2, 0x1B);
                                    emuHelper.writeMemoryValue(rcsrAddr, 2, 0x80);
                                } catch (Exception e) { /* best effort */ }
                                break;
                            case 'Y':
                                escState[0] = ST_ESC_Y_ROW;
                                return;
                            default:
                                // unrecognized/unimplemented - ignored for now
                                break;
                        }
                        escState[0] = ST_NORMAL;
                        redraw();
                        return;

                    case ST_ESC_Y_ROW:
                        escRow[0] = b - 32;
                        escState[0] = ST_ESC_Y_COL;
                        return;

                    case ST_ESC_Y_COL:
                        cur[0] = escRow[0];
                        cur[1] = b - 32;
                        clampCursor();
                        escState[0] = ST_NORMAL;
                        redraw();
                        return;

                    case ST_ESC_E_DATA:
                        escECount[0]++;
                        if (b >= 0x20 && b <= 0x7E) {
                            putChar((char) b);
                        } else if (b == 0x00) {
                            putChar(NUL_MARKER);
                        } else {
                            for (char c : String.format("[%02X]", b).toCharArray()) {
                                putChar(c);
                            }
                        }
                        if (escECount[0] >= 8) {
                            escState[0] = ST_NORMAL;
                        }
                        redraw();
                        return;

                    default:
                        if (b == 0x1B) {
                            escState[0] = ST_ESC;
                            return;
                        }
                        if (b == 0x0D) { // CR
                            cur[1] = 0;
                        } else if (b == 0x0A) { // LF
                            cur[0]++;
                            if (cur[0] >= ROWS) { scrollUp(); cur[0] = ROWS - 1; }
                        } else if (b == 0x00) {
                            putChar(NUL_MARKER); // visually flag suspected bug output
                        } else if (b < 0x20 || b == 0x7f) {
                            // other non-printing control bytes: skip silently for now
                        } else {
                            putChar((char) b);
                        }
                        redraw();
                }
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
                    String more = askString("Console Input @ PC=0x" + Long.toHexString(pc),
                        "Type text to send to the emulated console (blank = stop sending input):", "");
                    if (more != null && !more.isEmpty()) {
                        pending.append(more).append("\r");
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
        // Force one final redraw so the last frame isn't stuck behind the throttle
        StringBuilder finalSb = new StringBuilder();
        for (int r = 0; r < ROWS; r++) {
            finalSb.append(grid[r]);
            if (r < ROWS - 1) finalSb.append('\n');
        }
        String finalText = finalSb.toString();
        SwingUtilities.invokeLater(() -> screen.setText(finalText));

        emuHelper.dispose();
    }
}

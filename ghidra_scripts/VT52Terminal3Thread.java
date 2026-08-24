//PDP-11 VT52/15IE terminal - clean 3-part architecture.
//
//PART 1 (its own thread): VT52ScreenEmulator - pure screen/cursor logic.
//    Knows nothing about Ghidra or the emulator. Just consumes raw bytes
//    from outputQueue and updates a text grid + a visible window.
//
//PART 2 (runs on Swing's UI thread, via button clicks): keyboard input.
//    Decodes typed text (including ^X control-char notation) into bytes,
//    pushes them into inputQueue. Never touches the emulator directly.
//
//PART 3 (its own thread): the actual emulation loop. The ONLY part that
//    calls EmulatorHelper/MemoryAccessFilter. Never calls VT52 decoding or
//    Swing code directly - only ever does simple, non-blocking queue pushes
//    and pops. This is deliberately kept as simple as possible, since this
//    is the one thread that must never be allowed to stall.
//
//PART 4 (not yet implemented): direct poke/peek and interrupt-request UI.
//
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
import ghidra.program.model.listing.Instruction;
import javax.swing.*;
import java.awt.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class VT52Terminal3Thread extends GhidraScript {

    // ============================================================
    // PART 1: VT52 screen emulation logic.
    // Self-contained - could be tested with a hand-built byte array
    // and zero Ghidra/emulator involvement at all.
    // ============================================================
    static class VT52Screen implements Runnable {

        private static final int ROWS = 24;
        private static final int COLS = 80;
        private static final char NUL_MARKER = '\u2588';

        private static final String KOI7_CYRIL =
            "\u042e\u0410\u0411\u0426\u0414\u0415\u0424\u0413\u0425\u0418\u0419\u041a\u041b\u041c\u041d\u041e\u041f"
            + "\u042f\u0420\u0421\u0422\u0423\u0416\u0412\u042c\u042b\u0417\u0428\u042d\u0429\u0427";

        private final char[][] grid = new char[ROWS][COLS];
        private int curRow = 0, curCol = 0;

        private int escState = 0; // 0=normal,1=esc,2=escY-row,3=escY-col
        private int escY0 = 0;
        private boolean mode15IE = false;
        private boolean grfMode = false;
        private boolean langRus = false;

        private final BlockingQueue<Integer> outputQueue;
        private final JTextArea screenArea;
        private final JLabel statusLabel;
        private volatile boolean running = true;

        VT52Screen(BlockingQueue<Integer> outputQueue, JTextArea screenArea, JLabel statusLabel) {
            this.outputQueue = outputQueue;
            this.screenArea = screenArea;
            this.statusLabel = statusLabel;
            for (char[] row : grid) java.util.Arrays.fill(row, ' ');
        }

        void stop() { running = false; }

        @Override
        public void run() {
            while (running) {
                try {
                    int b = outputQueue.take(); // blocks quietly until a byte arrives - fine, this is its own thread
                    processByte(b);
                    redraw();
                } catch (InterruptedException e) {
                    break;
                }
            }
        }

        private void redraw() {
            StringBuilder sb = new StringBuilder();
            for (int r = 0; r < ROWS; r++) {
                sb.append(grid[r]);
                if (r < ROWS - 1) sb.append('\n');
            }
            String text = sb.toString();
            SwingUtilities.invokeLater(() -> screenArea.setText(text));
        }

        private void clampCursor() {
            if (curRow < 0) curRow = 0;
            if (curRow >= ROWS) curRow = ROWS - 1;
            if (curCol < 0) curCol = 0;
            if (curCol >= COLS) curCol = COLS - 1;
        }

        private void scrollUp() {
            for (int r = 0; r < ROWS - 1; r++) System.arraycopy(grid[r + 1], 0, grid[r], 0, COLS);
            java.util.Arrays.fill(grid[ROWS - 1], ' ');
        }

        private void putRawChar(char c) {
            grid[curRow][curCol] = c;
            curCol++;
            if (curCol >= COLS) {
                curCol = 0;
                curRow++;
                if (curRow >= ROWS) { scrollUp(); curRow = ROWS - 1; }
            }
        }

        private void putEscapeAtomic(String s) {
            if (curCol + s.length() > COLS) {
                curCol = 0;
                curRow++;
                if (curRow >= ROWS) { scrollUp(); curRow = ROWS - 1; }
            }
            for (char c : s.toCharArray()) { grid[curRow][curCol] = c; curCol++; }
        }

        private void home() { curRow = 0; curCol = 0; }
        private void bline() { curCol = 0; }
        private void lfeed() { curRow++; if (curRow >= ROWS) { scrollUp(); curRow = ROWS - 1; } }
        private void revlf() { curRow--; if (curRow < 0) { scrollUp(); curRow = 0; } }
        private void cup()    { if (curRow > 0) curRow--; }
        private void cdown()  { if (curRow < ROWS - 1) curRow++; }
        private void cleft()  { if (curCol > 0) curCol--; }
        private void cright() { if (curCol < COLS - 1) curCol++; }

        private void htab() {
            if (curCol >= COLS - 1) return;
            curCol = (curCol >= 72) ? curCol + 1 : 72;
            clampCursor();
        }

        private void clreol() { for (int x = curCol; x < COLS; x++) grid[curRow][x] = ' '; }
        private void clreos() {
            clreol();
            for (int r = curRow + 1; r < ROWS; r++) java.util.Arrays.fill(grid[r], ' ');
        }
        private void clscreen() { for (char[] row : grid) java.util.Arrays.fill(row, ' '); home(); }

        private void insblank() {
            for (int x = COLS - 1; x > curCol; x--) grid[curRow][x] = grid[curRow][x - 1];
            grid[curRow][curCol] = ' ';
        }
        private void delchar() {
            for (int x = curCol; x < COLS - 1; x++) grid[curRow][x] = grid[curRow][x + 1];
            grid[curRow][COLS - 1] = ' ';
        }

        private void curmove(int row, int col) {
            if (row < 0) row = 0; if (row >= ROWS) row = ROWS - 1;
            if (col < 0) col = 0; if (col >= COLS) col = COLS - 1;
            curRow = row; curCol = col;
        }

        private int koi7Translate(int r0) {
            if (r0 >= 0140 && r0 <= 0176) {
                int idx = r0 - 0140;
                if (idx < KOI7_CYRIL.length()) return KOI7_CYRIL.charAt(idx);
            }
            return r0;
        }

        private void putCharProcessed(int r0) {
            if (grfMode && r0 >= 0137 && r0 <= 0176) r0 += 041;
            else if (langRus) r0 = koi7Translate(r0);

            if (r0 == 0x00) putEscapeAtomic(String.valueOf(NUL_MARKER));
            else if (r0 <= 0x7E || (r0 >= 0x0400 && r0 <= 0x04FF)) putRawChar((char) r0);
            else putEscapeAtomic(String.format("[%02X]", r0));
        }

        private boolean ch15ie(int r0) {
            switch (r0) {
                case 010: home(); return true;
                case 013: clreol(); return true;
                case 014: clscreen(); return true;
                case 022: case 026: scrollUp(); return true;
                case 023: insblank(); return true;
                case 024: delchar(); return true;
                case 025:
                    bline(); curRow++;
                    if (curRow >= ROWS) { scrollUp(); curRow = ROWS - 1; }
                    return true;
                case 027: mode15IE = false; return true;
                case 031: cright(); return true;
                case 032: cleft(); return true;
                case 034: cup(); return true;
                case 035: cdown(); return true;
                default: return false;
            }
        }

        void processByte(int r0raw) {
            int r0 = r0raw & 0x7F;

            if (escState == 1) { // ESC seen, waiting for command byte
                switch (r0) {
                    case 'B': cdown(); break;
                    case 'A': cup(); break;
                    case 'C': cright(); break;
                    case 'D': cleft(); break;
                    case 'H': home(); break;
                    case 'Y': escState = 2; return;
                    case 'J': clreos(); break;
                    case 'K': clreol(); break;
                    case 0111: revlf(); break;
                    case 'E': mode15IE = true; break;
                    case 'F': grfMode = true; break;
                    case 'G': grfMode = false; break;
                    default: break;
                }
                escState = 0;
                return;
            }
            if (escState == 2) { escY0 = r0 - 32; escState = 3; return; }
            if (escState == 3) { curmove(escY0, r0 - 32); escState = 0; return; }

            if (r0 == 0x0D) { bline(); return; }
            if (r0 == 0x0A) { lfeed(); return; }
            if (r0 == 0x0F) { langRus = false; return; }
            if (r0 == 0x0E) { langRus = true; return; }
            if (r0 == 0x07) { return; }
            if (r0 == 0x7F) { putRawChar(NUL_MARKER); return; }

            if (mode15IE) {
                if (ch15ie(r0)) return;
            } else {
                if (r0 == 0x08) { cleft(); return; }
                if (r0 == 0x09) { htab(); return; }
                if (r0 == 0x0B || r0 == 0x0C) { lfeed(); return; }
                if (r0 == 0x1B) { escState = 1; return; }
            }

            putCharProcessed(r0);
        }
    }

    // ============================================================
    // PART 2: keyboard input. Runs on Swing's own thread (button
    // click handler) - never touches the emulator or the screen
    // logic directly. Only ever pushes decoded bytes into inputQueue.
    // ============================================================
    static void sendTextToQueue(String typed, BlockingQueue<Integer> inputQueue) {
        for (int i = 0; i < typed.length(); i++) {
            char ch = typed.charAt(i);
            if (ch == '^' && i + 1 < typed.length()) {
                char next = Character.toUpperCase(typed.charAt(i + 1));
                inputQueue.offer((int) (next & 0x1F));
                i++;
            } else {
                inputQueue.offer((int) ch);
            }
        }
    }

    // ============================================================
    // PART 3: the emulation loop. The ONLY part touching EmulatorHelper.
    // Deliberately as simple as possible - just non-blocking queue
    // pushes/pops, nothing else, so this thread can never stall.
    // ============================================================
    public void run() throws Exception {

        String startPCStr = askString("Start PC", "Enter starting PC (hex, no 0x):", "0080");
        long startPC = Long.parseLong(startPCStr.trim(), 16);
        String startSPStr = askString("Start SP", "Enter starting stack SP (hex, no 0x):", "5000");
        long startSP = Long.parseLong(startSPStr.trim(), 16);

        EmulatorHelper emuHelper = new EmulatorHelper(currentProgram);
        emuHelper.writeRegister("PC", startPC);
        emuHelper.writeRegister("PS", 0x0000);
        emuHelper.writeRegister("SP", startSP);

        Address xbufAddr = toAddr(0xFF76);
        Address rbufAddr = toAddr(0xFF72);
        Address rcsrAddr = toAddr(0xFF70);
        Address xcsrAddr = toAddr(0xFF74);

        emuHelper.writeMemoryValue(xcsrAddr, 2, 0x0080);
        emuHelper.writeMemoryValue(rcsrAddr, 2, 0x0000);

        // --- The two queues that are the ONLY connection between all three parts ---
        final BlockingQueue<Integer> outputQueue = new LinkedBlockingQueue<>();
        final BlockingQueue<Integer> inputQueue = new LinkedBlockingQueue<>();

        // --- UI setup (window, buttons - Part 2's home) ---
        JFrame frame = new JFrame("VT52 Terminal (3-thread architecture)");
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setAlwaysOnTop(true);
        final boolean[] windowOpen = { true };
        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            public void windowClosed(java.awt.event.WindowEvent e) {
                windowOpen[0] = false;
            }
        });

        JTextArea screenArea = new JTextArea(24, 80);
        screenArea.setEditable(false);
        screenArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
        JScrollPane screenScroll = new JScrollPane(screenArea);

        JLabel statusLabel = new JLabel("Starting...");
        JTextField keyInputField = new JTextField(20);
        JButton sendKeyButton = new JButton("Send to RBUF");
        JButton forceXcsrButton = new JButton("Force XCSR Ready");
        JButton stopDumpButton = new JButton("Stop && Dump State");
        JButton pauseButton = new JButton("Pause");
        JLabel liveRegsLabel = new JLabel("PC=---- (not started)");

        sendKeyButton.addActionListener(e -> {
            String typed = keyInputField.getText();
            keyInputField.setText("");
            if (typed != null && !typed.isEmpty()) {
                sendTextToQueue(typed, inputQueue);
            }
        });

        final boolean[] forceXcsrReady = { false };
        forceXcsrButton.addActionListener(e -> forceXcsrReady[0] = true);

        final boolean[] requestStop = { false };
        stopDumpButton.addActionListener(e -> requestStop[0] = true);

        final boolean[] paused = { false };
        pauseButton.addActionListener(e -> {
            paused[0] = !paused[0];
            SwingUtilities.invokeLater(() -> pauseButton.setText(paused[0] ? "Resume" : "Pause"));
        });

        JPanel content = new JPanel(new BorderLayout());
        content.add(new JLabel("PDP-11 VT52 Terminal", SwingConstants.CENTER), BorderLayout.NORTH);
        content.add(screenScroll, BorderLayout.CENTER);
        JPanel bottom = new JPanel(new BorderLayout());
        JPanel inputRow = new JPanel(new BorderLayout());
        inputRow.add(keyInputField, BorderLayout.CENTER);
        inputRow.add(sendKeyButton, BorderLayout.EAST);
        JPanel topButtons = new JPanel(new BorderLayout());
        topButtons.add(pauseButton, BorderLayout.WEST);
        topButtons.add(forceXcsrButton, BorderLayout.CENTER);
        topButtons.add(stopDumpButton, BorderLayout.EAST);
        bottom.add(topButtons, BorderLayout.NORTH);
        bottom.add(liveRegsLabel, BorderLayout.CENTER);
        bottom.add(inputRow, BorderLayout.CENTER);
        bottom.add(statusLabel, BorderLayout.SOUTH);
        content.add(bottom, BorderLayout.SOUTH);
        frame.setContentPane(content);
        frame.pack();
        frame.setMinimumSize(new Dimension(750, 500));
        frame.setLocationRelativeTo(null);
        SwingUtilities.invokeLater(() -> frame.setVisible(true));

        // --- Start Part 1 on its own thread ---
        VT52Screen screenLogic = new VT52Screen(outputQueue, screenArea, statusLabel);
        Thread screenThread = new Thread(screenLogic, "VT52-Screen-Thread");
        screenThread.setDaemon(true);
        screenThread.start();

        // --- Part 3's own memory-access hooks: ONLY ever push/pop queues ---
        emuHelper.getEmulator().addMemoryAccessFilter(new MemoryAccessFilter() {
            @Override
            protected void processRead(AddressSpace space, long offset, int size, byte[] values) {
                Address read = space.getAddress(offset);
                if (read.equals(rbufAddr)) {
                    try {
                        byte[] c = emuHelper.readMemory(rcsrAddr, 2);
                        int val = (c[0] & 0xFF) | ((c[1] & 0xFF) << 8);
                        emuHelper.writeMemoryValue(rcsrAddr, 2, val & ~0x80);
                    } catch (Exception e) { /* best effort */ }
                } else if (read.equals(xcsrAddr)) {
                    try {
                        byte[] c = emuHelper.readMemory(xcsrAddr, 2);
                        int val = (c[0] & 0xFF) | ((c[1] & 0xFF) << 8);
                        emuHelper.writeMemoryValue(xcsrAddr, 2, val | 0x80);
                    } catch (Exception e) { /* best effort */ }
                }
            }

            @Override
            protected void processWrite(AddressSpace space, long offset, int size, byte[] values) {
                Address written = space.getAddress(offset);
                if (written.equals(xbufAddr)) {
                    // Non-blocking hand-off only - never touches VT52 logic directly.
                    outputQueue.offer(values[0] & 0xFF);
                    try {
                        emuHelper.writeMemoryValue(xcsrAddr, 2, 0x0000);
                    } catch (Exception e) { /* best effort */ }
                }
            }
        });

        // --- Part 3's main loop: simple, non-blocking, nothing but stepping ---
        try {
            while (windowOpen[0] && !monitor.isCancelled() && !requestStop[0]) {
                long pc = emuHelper.readRegister("PC").longValue();
                Instruction instr = currentProgram.getListing().getInstructionAt(toAddr(pc));
                String instrText = (instr != null) ? instr.toString() : "??";

                // Live display, updated every iteration BEFORE stepping - lets you
                // see exactly where execution is, pause, and read it off, without
                // needing to fully stop (unlike Stop & Dump, which ends everything).
                String liveLabel = String.format("PC=%04X  %s", pc, instrText);
                SwingUtilities.invokeLater(() -> liveRegsLabel.setText(liveLabel));

                if (instrText.trim().startsWith("HALT")) {
                    String haltMsg = "Reached HALT at PC=0x" + Long.toHexString(pc);
                    SwingUtilities.invokeLater(() -> statusLabel.setText(haltMsg));
                    break;
                }

                if (forceXcsrReady[0]) {
                    forceXcsrReady[0] = false;
                    emuHelper.writeMemoryValue(xcsrAddr, 2, 0x0080);
                }

                if (paused[0]) {
                    // Idle without stepping the emulator - still processes all the
                    // buttons above every iteration, so XCSR/input/stop controls
                    // remain fully usable while paused.
                    Thread.sleep(50);
                    continue;
                }

                byte[] rcsrBytes = emuHelper.readMemory(rcsrAddr, 2);
                int rcsrVal = (rcsrBytes[0] & 0xFF) | ((rcsrBytes[1] & 0xFF) << 8);
                boolean ready = (rcsrVal & 0x80) != 0;

                if (!ready) {
                    Integer nextKey = inputQueue.poll(); // non-blocking - null if nothing waiting
                    if (nextKey != null) {
                        emuHelper.writeMemoryValue(rbufAddr, 2, (long) nextKey & 0xFF);
                        emuHelper.writeMemoryValue(rcsrAddr, 2, 0x80);
                    }
                }

                emuHelper.step(monitor);
            }
        } catch (Exception ex) {
            println("Emulation stopped due to exception: " + ex);
        } finally {
            // Guaranteed to run no matter how the loop above exited -
            // normal stop, HALT, window closed, Stop&Dump button, or a
            // genuine crash/exception. Same dump format as the other scripts.
            try {
                long pc = emuHelper.readRegister("PC").longValue();
                long r0 = emuHelper.readRegister("R0").longValue() & 0xFFFF;
                long r1 = emuHelper.readRegister("R1").longValue() & 0xFFFF;
                long r2 = emuHelper.readRegister("R2").longValue() & 0xFFFF;
                long r3 = emuHelper.readRegister("R3").longValue() & 0xFFFF;
                long r4 = emuHelper.readRegister("R4").longValue() & 0xFFFF;
                long r5 = emuHelper.readRegister("R5").longValue() & 0xFFFF;
                long sp = emuHelper.readRegister("SP").longValue() & 0xFFFF;
                long ps = emuHelper.readRegister("PS").longValue() & 0xFFFF;
                byte[] rc = emuHelper.readMemory(rcsrAddr, 2);
                byte[] rb = emuHelper.readMemory(rbufAddr, 2);
                byte[] xc = emuHelper.readMemory(xcsrAddr, 2);
                byte[] xb = emuHelper.readMemory(xbufAddr, 2);
                int rcVal = (rc[0] & 0xFF) | ((rc[1] & 0xFF) << 8);
                int rbVal = (rb[0] & 0xFF) | ((rb[1] & 0xFF) << 8);
                int xcVal = (xc[0] & 0xFF) | ((xc[1] & 0xFF) << 8);
                int xbVal = (xb[0] & 0xFF) | ((xb[1] & 0xFF) << 8);
                println(String.format(
                    "Stopped @ PC=%04X R0=%04X R1=%04X R2=%04X R3=%04X R4=%04X R5=%04X SP=%04X PS=%04X RAM: [FF70]=%04X [FF72]=%04X [FF74]=%04X [FF76]=%04X",
                    pc, r0, r1, r2, r3, r4, r5, sp, ps, rcVal, rbVal, xcVal, xbVal));
            } catch (Exception dumpEx) {
                println("Could not read final state for dump: " + dumpEx);
            }

            screenLogic.stop();
            emuHelper.dispose();
        }
    }
}

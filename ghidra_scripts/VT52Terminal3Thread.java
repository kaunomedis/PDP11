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
import java.util.concurrent.atomic.AtomicInteger;

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

        // Real form dialog: one labeled text field per value, all shown at
        // once in a single window - easier to read/use than a single
        // space-separated string. Covers the full PDP-11 register set:
        // R0-R5, SP (=R6), PC (=R7), and PS (the processor status word).
        String[] labels = {
            "R0 (hex, no 0x):", "R1 (hex, no 0x):", "R2 (hex, no 0x):",
            "R3 (hex, no 0x):", "R4 (hex, no 0x):", "R5 (hex, no 0x):",
            "SP (hex, no 0x):", "PC (hex, no 0x):", "PS (hex, no 0x):"
        };
        String[] defaults = {
            "0000", "0000", "0000",
            "0000", "0000", "0000",
            "5000", "0080", "0000"
        };
        JTextField[] fields = new JTextField[labels.length];

        JPanel formPanel = new JPanel(new java.awt.GridLayout(labels.length, 2, 5, 5));
        for (int i = 0; i < labels.length; i++) {
            formPanel.add(new JLabel(labels[i]));
            fields[i] = new JTextField(defaults[i]);
            formPanel.add(fields[i]);
        }

        // JOptionPane blocks the calling thread until dismissed - but it
        // must be SHOWN on Swing's own thread (the EDT), not this script's
        // thread. invokeAndWait() safely bridges the two: it runs the given
        // code on the EDT and doesn't return here until that code (and the
        // dialog it shows) is fully finished.
        final int[] dialogResult = { JOptionPane.CANCEL_OPTION };
        SwingUtilities.invokeAndWait(() -> {
            dialogResult[0] = JOptionPane.showConfirmDialog(null, formPanel,
                "Initial Register Values", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        });

        if (dialogResult[0] != JOptionPane.OK_OPTION) {
            println("Cancelled - no starting values entered.");
            return;
        }

        long startR0 = Long.parseLong(fields[0].getText().trim(), 16);
        long startR1 = Long.parseLong(fields[1].getText().trim(), 16);
        long startR2 = Long.parseLong(fields[2].getText().trim(), 16);
        long startR3 = Long.parseLong(fields[3].getText().trim(), 16);
        long startR4 = Long.parseLong(fields[4].getText().trim(), 16);
        long startR5 = Long.parseLong(fields[5].getText().trim(), 16);
        long startSP = Long.parseLong(fields[6].getText().trim(), 16);
        long startPC = Long.parseLong(fields[7].getText().trim(), 16);
        long startPS = Long.parseLong(fields[8].getText().trim(), 16);

        EmulatorHelper emuHelper = new EmulatorHelper(currentProgram);
        emuHelper.writeRegister("R0", startR0);
        emuHelper.writeRegister("R1", startR1);
        emuHelper.writeRegister("R2", startR2);
        emuHelper.writeRegister("R3", startR3);
        emuHelper.writeRegister("R4", startR4);
        emuHelper.writeRegister("R5", startR5);
        emuHelper.writeRegister("SP", startSP);
        emuHelper.writeRegister("PC", startPC);
        emuHelper.writeRegister("PS", startPS);

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
        screenArea.setFont(new Font("Consolas", Font.PLAIN, 14));
		
        JScrollPane screenScroll = new JScrollPane(screenArea);

        JLabel statusLabel = new JLabel("Starting...");
        JTextField keyInputField = new JTextField(20);
        JButton sendKeyButton = new JButton("Send to RBUF");
        JButton forceXcsrButton = new JButton("Force XCSR Ready");
        JButton stopDumpButton = new JButton("Stop && Dump State");
        JButton pauseButton = new JButton("Pause");
        JButton dumpRamButton = new JButton("Dump RAM to File");
        // (liveRegsLabel removed - updating it every single step flooded Swing's
        // event queue and throttled the emulation; the Pause button's one-shot
        // console dump already covers this need without the per-step overhead.)

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

        // ============================================================
        // UART TIMING SIMULATION - "countdown" model
        // ============================================================
        //
        // THE PROBLEM THIS SOLVES:
        // Real 1980s UART hardware is MUCH SLOWER than the CPU reading it.
        // So on real hardware, a program's "wait for a character" poll loop
        // naturally spins hundreds or thousands of times before a new
        // character actually shows up - the slow UART is the bottleneck,
        // never the fast CPU.
        //
        // Our emulator did the OPPOSITE: the instant we (Java) decided to
        // inject a character, we set the "ready" bit immediately - zero
        // delay at all. Some real PDP-11 programs (like the BASIC we tested)
        // apparently rely on there being SOME minimum number of poll
        // iterations between characters (maybe as part of their own
        // debounce/confirmation logic). Injecting instantly broke that
        // assumption, and characters got silently skipped or overwritten.
        //
        // THE FIX - a countdown counter, like a hardware timer/counter chip:
        //   - Starts at 0, meaning "ready right now". This is why the very
        //     FIRST character (or a single character sent alone) always
        //     worked correctly even before this fix - there's no artificial
        //     delay before the very first injection.
        //   - Every time the EMULATED PDP-11 PROGRAM touches RBUF or RCSR
        //     (either reading OR writing either one - see below for why we
        //     count all four combinations), we decrement the counter by 1,
        //     but ONLY IF it is currently ABOVE zero. If it's already at
        //     zero, touching RBUF/RCSR does nothing to the counter - it
        //     just stays at zero, "ready", waiting for us to inject.
        //   - Our OWN main loop checks: "is the counter at exactly zero
        //     right now?" If yes, and there's a character waiting in the
        //     queue, we inject it AND re-arm the counter back up to
        //     COUNTDOWN_START - starting a brand new countdown before the
        //     NEXT character can go in.
        //
        // WHY COUNT ALL FOUR (RBUF read, RBUF write, RCSR read, RCSR write)
        // INSTEAD OF JUST "RBUF reads"?
        // Different PDP-11 programs poll differently - some check RCSR
        // twice before ever touching RBUF (we saw exactly this in one
        // disassembly: a TST then a TSTB, both reading RCSR, BEFORE the
        // actual RBUF read). If we only counted RBUF reads, a program that
        // spends most of its polling time checking RCSR (not RBUF) would
        // never advance the countdown at all. Counting every touch of
        // either register makes the countdown track "how much real polling
        // activity has happened" in general, regardless of which exact
        // register a given program happens to check most.
        //
        // WHY NOT JUST DECREMENT WITHOUT CHECKING FOR ZERO FIRST?
        // If we decremented unconditionally (even when already at 0), fast
        // or overlapping activity could push the counter into NEGATIVE
        // numbers. A plain "if (count == 0)" check somewhere else in the
        // code would then never become true again - the counter would sail
        // past zero without us ever noticing, and we'd stop injecting
        // characters forever. This is a real risk in multi-threaded code:
        // if two things tried to decrement at once with no protection, you
        // could lose track of the exact moment the counter crosses zero.
        // Guarding every decrement with "only if > 0" prevents this - the
        // counter can never go below zero, period, no matter how many times
        // or how quickly something tries to decrement it.
        //
        // WHY AtomicInteger INSTEAD OF A PLAIN int/int[]?
        // A plain "int" (or a 1-element int[] used as a poor-man's mutable
        // reference, which is what we used elsewhere in this file for
        // simple flags) is NOT safe if two different threads ever read and
        // write it at the same time - you can get corrupted, inconsistent
        // values ("race conditions"). In our CURRENT code, only the
        // emulation thread ever touches this counter, so a plain int would
        // likely work fine today - but AtomicInteger costs us nothing and
        // makes this permanently safe even if the code changes later (for
        // example, if we ever add a second way to trigger a decrement from
        // a different thread). getAndDecrement()/compareAndSet() style
        // operations on AtomicInteger are guaranteed atomic - the "check if
        // greater than zero, then decrement" happens as one indivisible
        // step, so no other thread can sneak in between the check and the
        // decrement and cause a bad value.
        //
        // COUNTDOWN_START is a GUESSED starting value, not a measured one.
        // It represents "how many RBUF/RCSR touches should happen before we
        // allow the next character in". Tune this by testing against real
        // programs: too LOW and you'll see skipped/overwritten characters
        // again; too HIGH and typing will feel artificially slow. Adjust
        // this one number to experiment - nothing else needs to change.
        final AtomicInteger uartCountdown = new AtomicInteger(0);
        final int COUNTDOWN_START = 8;

        final boolean[] dumpOnPauseRequested = { false };
        pauseButton.addActionListener(e -> {
            paused[0] = !paused[0];
            if (paused[0]) dumpOnPauseRequested[0] = true; // only dump when PAUSING, not on resume
            SwingUtilities.invokeLater(() -> pauseButton.setText(paused[0] ? "Resume" : "Pause"));
        });

        final boolean[] ramDumpRequested = { false };
        dumpRamButton.addActionListener(e -> ramDumpRequested[0] = true);

        JPanel content = new JPanel(new BorderLayout());
        content.add(new JLabel("PDP-11 VT52 Terminal", SwingConstants.CENTER), BorderLayout.NORTH);
        content.add(screenScroll, BorderLayout.CENTER);
        JPanel bottom = new JPanel(new BorderLayout());
        JPanel inputRow = new JPanel(new BorderLayout());
        inputRow.add(keyInputField, BorderLayout.CENTER);
        inputRow.add(sendKeyButton, BorderLayout.EAST);
        JPanel topButtons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT));
        topButtons.add(pauseButton);
        topButtons.add(forceXcsrButton);
        topButtons.add(dumpRamButton);
        topButtons.add(stopDumpButton);
        bottom.add(topButtons, BorderLayout.NORTH);
        JPanel lowerPanel = new JPanel(new java.awt.GridLayout(2, 1));
        lowerPanel.add(inputRow);
        lowerPanel.add(statusLabel);
        bottom.add(lowerPanel, BorderLayout.CENTER);
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

            // Small local helper: decrements uartCountdown by exactly 1, but
            // ONLY if it's currently above zero. This is the one piece of
            // logic that must run identically for all four RBUF/RCSR touch
            // combinations below, so it's written once here instead of
            // copy-pasted four times.
            private void tickUartCountdown() {
                uartCountdown.updateAndGet(current -> current > 0 ? current - 1 : current);
            }

            @Override
            protected void processRead(AddressSpace space, long offset, int size, byte[] values) {
                Address read = space.getAddress(offset);

                if (read.equals(rbufAddr)) {
                    // Real hardware behavior (confirmed from the UART
                    // datasheet): reading RBUF clears RCSR's "Done"/ready
                    // bit automatically. This is genuinely correct hardware
                    // emulation and is UNRELATED to our own countdown timing
                    // trick below - both happen here, for different reasons.
                    try {
                        byte[] c = emuHelper.readMemory(rcsrAddr, 2);
                        int val = (c[0] & 0xFF) | ((c[1] & 0xFF) << 8);
                        emuHelper.writeMemoryValue(rcsrAddr, 2, val & ~0x80);
                    } catch (Exception e) { /* best effort */ }
                    tickUartCountdown();

                } else if (read.equals(rcsrAddr)) {
                    // The program's own "TSTB RCSR" / "TST RCSR" style poll
                    // check. No hardware side-effect needed here (checking a
                    // status register doesn't change it on real hardware) -
                    // but this IS real polling activity, so it still counts
                    // toward the countdown.
                    tickUartCountdown();

                } else if (read.equals(xcsrAddr)) {
                    try {
                        byte[] c = emuHelper.readMemory(xcsrAddr, 2);
                        int val = (c[0] & 0xFF) | ((c[1] & 0xFF) << 8);
                        emuHelper.writeMemoryValue(xcsrAddr, 2, val | 0x80);
                    } catch (Exception e) { /* best effort */ }
                    // XCSR/XBUF are the OUTPUT (transmit) side - deliberately
                    // NOT counted here, since this timing model is only for
                    // input (keyboard) pacing.
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
                    // Output side, not counted - see note above.

                } else if (written.equals(rbufAddr)) {
                    // Unusual (the CPU writing to its OWN receive buffer),
                    // but if some program does this as part of a reset/self-
                    // test sequence, it's still real activity on this
                    // register, so it counts the same as everything else.
                    tickUartCountdown();

                } else if (written.equals(rcsrAddr)) {
                    // The program's own writes to RCSR (e.g. "CLR RCSR" seen
                    // in the real disassembly). We do NOT know for certain
                    // whether this is genuinely part of the input-pacing
                    // rhythm or unrelated housekeeping - but since the
                    // countdown model only cares about "how much polling
                    // activity happened", it's safe and consistent to count
                    // this too, the same as the other three combinations.
                    tickUartCountdown();
                }
            }
        });

        // Shared dump logic, used both by Pause and the final stop - same format,
        // just a different prefix word ("Paused"/"Stopped").
        java.util.function.Consumer<String> dumpState = (prefix) -> {
            try {
                long dpc = emuHelper.readRegister("PC").longValue() & 0xFFFF;
                long dr0 = emuHelper.readRegister("R0").longValue() & 0xFFFF;
                long dr1 = emuHelper.readRegister("R1").longValue() & 0xFFFF;
                long dr2 = emuHelper.readRegister("R2").longValue() & 0xFFFF;
                long dr3 = emuHelper.readRegister("R3").longValue() & 0xFFFF;
                long dr4 = emuHelper.readRegister("R4").longValue() & 0xFFFF;
                long dr5 = emuHelper.readRegister("R5").longValue() & 0xFFFF;
                long dsp = emuHelper.readRegister("SP").longValue() & 0xFFFF;
                long dps = emuHelper.readRegister("PS").longValue() & 0xFFFF;
                byte[] drc = emuHelper.readMemory(rcsrAddr, 2);
                byte[] drb = emuHelper.readMemory(rbufAddr, 2);
                byte[] dxc = emuHelper.readMemory(xcsrAddr, 2);
                byte[] dxb = emuHelper.readMemory(xbufAddr, 2);
                int drcVal = (drc[0] & 0xFF) | ((drc[1] & 0xFF) << 8);
                int drbVal = (drb[0] & 0xFF) | ((drb[1] & 0xFF) << 8);
                int dxcVal = (dxc[0] & 0xFF) | ((dxc[1] & 0xFF) << 8);
                int dxbVal = (dxb[0] & 0xFF) | ((dxb[1] & 0xFF) << 8);
                println(String.format(
                    "%s @ PC=%04X R0=%04X R1=%04X R2=%04X R3=%04X R4=%04X R5=%04X SP=%04X PS=%04X RAM: [FF70]=%04X [FF72]=%04X [FF74]=%04X [FF76]=%04X",
                    prefix, dpc, dr0, dr1, dr2, dr3, dr4, dr5, dsp, dps, drcVal, drbVal, dxcVal, dxbVal));
            } catch (Exception dumpEx) {
                println("Could not read state for dump: " + dumpEx);
            }
        };

        // --- Part 3's main loop: simple, non-blocking, nothing but stepping ---
        try {
            while (windowOpen[0] && !monitor.isCancelled() && !requestStop[0]) {
                long pc = emuHelper.readRegister("PC").longValue();
                Instruction instr = currentProgram.getListing().getInstructionAt(toAddr(pc));
                String instrText = (instr != null) ? instr.toString() : "??";

                if (instrText.trim().startsWith("HALT")) {
                    String haltMsg = "Reached HALT at PC=0x" + Long.toHexString(pc);
                    SwingUtilities.invokeLater(() -> statusLabel.setText(haltMsg));
                    break;
                }

                if (forceXcsrReady[0]) {
                    forceXcsrReady[0] = false;
                    emuHelper.writeMemoryValue(xcsrAddr, 2, 0x0080);
                }

                if (ramDumpRequested[0]) {
                    ramDumpRequested[0] = false;
                    String ramPath = "C:/GHIDRA/ram_dump_" + Long.toHexString(pc) + ".bin";
                    try {
                        byte[] fullRam = emuHelper.readMemory(toAddr(0x0000), 0x10000);
                        java.io.FileOutputStream fos = new java.io.FileOutputStream(ramPath);
                        fos.write(fullRam);
                        fos.close();
                        println("RAM dumped to " + ramPath + " (65536 bytes)");
                    } catch (Exception ramEx) {
                        println("RAM dump failed: " + ramEx);
                    }
                }

                if (paused[0]) {
                    if (dumpOnPauseRequested[0]) {
                        dumpOnPauseRequested[0] = false;
                        dumpState.accept("Paused");
                    }
                    // Idle without stepping the emulator - still processes all the
                    // buttons above every iteration, so XCSR/input/stop controls
                    // remain fully usable while paused.
                    Thread.sleep(50);
                    continue;
                }

                // Only inject the next character once the countdown has
                // genuinely reached zero (see the big comment block above,
                // near where uartCountdown/COUNTDOWN_START are declared, for
                // the full explanation of why this exists and how it works).
                if (uartCountdown.get() == 0) {
                    Integer nextKey = inputQueue.poll(); // non-blocking - null if nothing waiting
                    if (nextKey != null) {
                        emuHelper.writeMemoryValue(rbufAddr, 2, (long) nextKey & 0xFF);
                        emuHelper.writeMemoryValue(rcsrAddr, 2, 0x80);
                        uartCountdown.set(COUNTDOWN_START); // re-arm for the NEXT character
                    }
                }

                emuHelper.step(monitor);
            }
        } catch (Exception ex) {
            println("Emulation stopped due to exception: " + ex);
        } finally {
            // Guaranteed to run no matter how the loop above exited -
            // normal stop, HALT, window closed, Stop&Dump button, or a
            // genuine crash/exception. Same dump format the Pause button uses.
            dumpState.accept("Stopped");

            screenLogic.stop();
            emuHelper.dispose();
        }
    }
}

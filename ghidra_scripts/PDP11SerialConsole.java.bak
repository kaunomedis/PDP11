//PDP-11 console (DL11 at 177560-177566) redirected to a real or virtual COM port.
//
//Needs jSerialComm jar in Ghidra/patch (restart Ghidra after adding it).
//Line format: 8N1, no flow control, baud chosen at start (default 9600).
//
//THREADS:
//  RX thread   : blocking read from COM port -> rxQueue (never touches emulator)
//  TX thread   : txQueue -> COM port          (never touches emulator)
//  Script thread: the emulation loop. The ONLY one calling EmulatorHelper.
//
//DL11 TIMING MODEL (wall clock, one character = 10 bits / baud):
//  TX: program writes XBUF -> XCSR.READY (bit 7) cleared, byte queued.
//      READY comes back one character time later.
//  RX: next byte is put into RBUF and RCSR.DONE (bit 7) set only when
//      - previous byte was taken (program read RBUF), and
//      - one character time passed since previous byte was delivered.
//      Bytes are never lost (no overrun emulation) - they wait in rxQueue.
//      This also paces input from virtual ports, which ignore baud rate.
//  Only bit 7 of RCSR/XCSR is controlled by Java. All other bits (e.g.
//  bit 6 = interrupt enable) are left as the program wrote them - ready
//  for future interrupt emulation.
//
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
import com.fazecast.jSerialComm.SerialPort;
import javax.swing.*;
import java.awt.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class PDP11SerialConsole extends GhidraScript {

    // DL11 console registers (octal 177560..177566)
    static final int RCSR = 0xFF70;
    static final int RBUF = 0xFF72;
    static final int XCSR = 0xFF74;
    static final int XBUF = 0xFF76;
    static final int BIT7 = 0x80;

    // --- shared state between the memory filter and the main loop ---
    // Only the script (emulation) thread touches these, filter runs on it too.
    private boolean txReady = true;     // XCSR bit 7
    private boolean rxDone = false;     // RCSR bit 7
    private boolean csrDirty = true;    // re-write bit 7 of both CSRs after next step
    private long txReadyAt = 0;         // System.nanoTime() when TX becomes ready
    private long rxNextAllowed = 0;     // System.nanoTime() when next RX byte may come

    private volatile boolean running = true;

    // ============================================================
    // RX thread: COM port -> rxQueue
    // ============================================================
    static class RxThread implements Runnable {
        private final SerialPort port;
        private final BlockingQueue<Integer> rxQueue;
        private final AtomicLong rxCount;
        private final PDP11SerialConsole owner;

        RxThread(SerialPort port, BlockingQueue<Integer> q, AtomicLong cnt, PDP11SerialConsole owner) {
            this.port = port; this.rxQueue = q; this.rxCount = cnt; this.owner = owner;
        }

        public void run() {
            byte[] buf = new byte[256];
            while (owner.running) {
                int n = port.readBytes(buf, buf.length); // semi-blocking, 100 ms timeout
                if (n < 0) break;                         // port closed / error
                for (int i = 0; i < n; i++) {
                    rxQueue.offer(buf[i] & 0xFF);
                    rxCount.incrementAndGet();
                }
            }
        }
    }

    // ============================================================
    // TX thread: txQueue -> COM port
    // ============================================================
    static class TxThread implements Runnable {
        private final SerialPort port;
        private final BlockingQueue<Integer> txQueue;
        private final AtomicLong txCount;
        private final PDP11SerialConsole owner;

        TxThread(SerialPort port, BlockingQueue<Integer> q, AtomicLong cnt, PDP11SerialConsole owner) {
            this.port = port; this.txQueue = q; this.txCount = cnt; this.owner = owner;
        }

        public void run() {
            byte[] one = new byte[1];
            while (owner.running) {
                try {
                    Integer b = txQueue.poll(100, TimeUnit.MILLISECONDS);
                    if (b == null) continue;
                    one[0] = (byte) (b & 0xFF);
                    port.writeBytes(one, 1);
                    txCount.incrementAndGet();
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
    }

    // read-modify-write of bit 7 only, all other CSR bits preserved
    private void setBit7(EmulatorHelper emu, Address a, boolean on) throws Exception {
        byte[] c = emu.readMemory(a, 2);
        int val = (c[0] & 0xFF) | ((c[1] & 0xFF) << 8);
        int nv = on ? (val | BIT7) : (val & ~BIT7);
        if (nv != val) emu.writeMemoryValue(a, 2, nv);
    }

    // ============================================================
    // Script / emulation thread
    // ============================================================
    public void run() throws Exception {

        // ---------- start dialog: COM port, baud, registers ----------
        SerialPort[] ports = SerialPort.getCommPorts();
        String[] portNames = new String[ports.length];
        for (int i = 0; i < ports.length; i++) {
            portNames[i] = ports[i].getSystemPortName() + "  (" + ports[i].getPortDescription() + ")";
        }
        JComboBox<String> portBox = new JComboBox<>(portNames);
        portBox.setEditable(true); // allow typing e.g. COM7 if not listed

        JComboBox<String> baudBox = new JComboBox<>(new String[] {
            "300", "1200", "2400", "4800", "9600", "19200", "38400", "57600", "115200" });
        baudBox.setEditable(true);
        baudBox.setSelectedItem("9600");

        String[] labels = {
            "R0 (hex):", "R1 (hex):", "R2 (hex):", "R3 (hex):", "R4 (hex):", "R5 (hex):",
            "SP (hex):", "PC (hex):", "PS (hex):"
        };
        String[] defaults = { "0000", "0000", "0000", "0000", "0000", "0000", "5000", "0080", "0000" };
        JTextField[] fields = new JTextField[labels.length];

        JPanel form = new JPanel(new GridLayout(labels.length + 2, 2, 5, 5));
        form.add(new JLabel("COM port:"));
        form.add(portBox);
        form.add(new JLabel("Baud (8N1):"));
        form.add(baudBox);
        for (int i = 0; i < labels.length; i++) {
            form.add(new JLabel(labels[i]));
            fields[i] = new JTextField(defaults[i]);
            form.add(fields[i]);
        }

        final int[] dlg = { JOptionPane.CANCEL_OPTION };
        SwingUtilities.invokeAndWait(() -> dlg[0] = JOptionPane.showConfirmDialog(null, form,
            "PDP-11 serial console", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE));
        if (dlg[0] != JOptionPane.OK_OPTION) { println("Cancelled."); return; }

        Object sel = portBox.getSelectedItem();
        if (sel == null || sel.toString().trim().isEmpty()) { println("No COM port selected."); return; }
        String portName = sel.toString().trim();
        int sp = portName.indexOf(' ');
        if (sp > 0) portName = portName.substring(0, sp); // strip "(description)"

        int baud = Integer.parseInt(baudBox.getSelectedItem().toString().trim());
        final long charNanos = 10L * 1_000_000_000L / baud; // start + 8 data + stop

        // ---------- open COM port ----------
        SerialPort port = SerialPort.getCommPort(portName);
        port.setComPortParameters(baud, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);
        port.setFlowControl(SerialPort.FLOW_CONTROL_DISABLED);
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING | SerialPort.TIMEOUT_WRITE_BLOCKING, 100, 0);
        if (!port.openPort()) {
            println("Cannot open " + portName + " (error " + port.getLastErrorCode() + ")");
            return;
        }
        println("Opened " + portName + " at " + baud + " 8N1, char time " + (charNanos / 1000) + " us");

        // ---------- emulator ----------
        EmulatorHelper emu = new EmulatorHelper(currentProgram);
        String[] regs = { "R0", "R1", "R2", "R3", "R4", "R5", "SP", "PC", "PS" };
        for (int i = 0; i < regs.length; i++) {
            emu.writeRegister(regs[i], Long.parseLong(fields[i].getText().trim(), 16));
        }

        final Address rcsrAddr = toAddr(RCSR);
        final Address rbufAddr = toAddr(RBUF);
        final Address xcsrAddr = toAddr(XCSR);
        final Address xbufAddr = toAddr(XBUF);

        emu.writeMemoryValue(rcsrAddr, 2, 0x0000);
        emu.writeMemoryValue(xcsrAddr, 2, BIT7);

        final BlockingQueue<Integer> rxQueue = new LinkedBlockingQueue<>();
        final BlockingQueue<Integer> txQueue = new LinkedBlockingQueue<>();
        final AtomicLong rxCount = new AtomicLong();
        final AtomicLong txCount = new AtomicLong();

        // ---------- small control window ----------
        final boolean[] paused = { false };
        final boolean[] requestStop = { false };
        final boolean[] dumpRequested = { false };

        JFrame frame = new JFrame("PDP-11 console on " + portName + " @ " + baud);
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            public void windowClosed(java.awt.event.WindowEvent e) { requestStop[0] = true; }
        });
        JLabel status = new JLabel("Starting...");
        JButton pauseBtn = new JButton("Pause");
        JButton dumpBtn = new JButton("Dump state");
        JButton stopBtn = new JButton("Stop");
        pauseBtn.addActionListener(e -> {
            paused[0] = !paused[0];
            pauseBtn.setText(paused[0] ? "Resume" : "Pause");
        });
        dumpBtn.addActionListener(e -> dumpRequested[0] = true);
        stopBtn.addActionListener(e -> requestStop[0] = true);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        buttons.add(pauseBtn);
        buttons.add(dumpBtn);
        buttons.add(stopBtn);
        JPanel content = new JPanel(new BorderLayout());
        content.add(buttons, BorderLayout.NORTH);
        content.add(status, BorderLayout.SOUTH);
        frame.setContentPane(content);
        frame.pack();
        frame.setMinimumSize(new Dimension(520, 110));
        frame.setLocationRelativeTo(null);
        SwingUtilities.invokeLater(() -> frame.setVisible(true));

        final long[] lastPc = { 0 };
        javax.swing.Timer statusTimer = new javax.swing.Timer(250, e -> status.setText(String.format(
            "PC=%04X  TX=%d (queued %d)  RX=%d (waiting %d)  XCSR.RDY=%d  RCSR.DONE=%d%s",
            lastPc[0] & 0xFFFF, txCount.get(), txQueue.size(), rxCount.get(), rxQueue.size(),
            txReady ? 1 : 0, rxDone ? 1 : 0, paused[0] ? "  PAUSED" : "")));
        statusTimer.start();

        // ---------- I/O threads ----------
        Thread rxThread = new Thread(new RxThread(port, rxQueue, rxCount, this), "PDP11-COM-RX");
        Thread txThread = new Thread(new TxThread(port, txQueue, txCount, this), "PDP11-COM-TX");
        rxThread.setDaemon(true);
        txThread.setDaemon(true);
        rxThread.start();
        txThread.start();

        // ---------- DL11 register hooks (run on this thread, inside step) ----------
        emu.getEmulator().addMemoryAccessFilter(new MemoryAccessFilter() {
            @Override
            protected void processRead(AddressSpace space, long offset, int size, byte[] values) {
                Address a = space.getAddress(offset);
                if (a.equals(rbufAddr)) {
                    rxDone = false;            // reading RBUF clears RCSR.DONE
                    csrDirty = true;
                }
            }

            @Override
            protected void processWrite(AddressSpace space, long offset, int size, byte[] values) {
                Address a = space.getAddress(offset);
                if (a.equals(xbufAddr)) {
                    txQueue.offer(values[0] & 0xFF);
                    txReady = false;
                    txReadyAt = System.nanoTime() + charNanos;
                    csrDirty = true;
                } else if (a.equals(xcsrAddr) || a.equals(rcsrAddr)) {
                    // program wrote a CSR (e.g. interrupt enable) - bit 7 is
                    // read-only on real hardware, so restore it after the step
                    csrDirty = true;
                }
            }
        });

        // ---------- main loop ----------
        try {
            while (!requestStop[0] && !monitor.isCancelled()) {
                long pc = emu.readRegister("PC").longValue();
                lastPc[0] = pc;

                if (dumpRequested[0]) { dumpRequested[0] = false; dumpState(emu, "Dump"); }

                if (paused[0]) { Thread.sleep(50); continue; }

                Instruction instr = currentProgram.getListing().getInstructionAt(toAddr(pc));
                if (instr != null && instr.getMnemonicString().equalsIgnoreCase("HALT")) {
                    println(String.format("HALT at PC=%04X", pc & 0xFFFF));
                    break;
                }

                long now = System.nanoTime();

                // TX: transmitter becomes free one character time after XBUF write
                if (!txReady && now >= txReadyAt) {
                    txReady = true;
                    csrDirty = true;
                }

                // RX: deliver next byte only if previous was read and line time passed
                if (!rxDone && now >= rxNextAllowed) {
                    Integer b = rxQueue.poll();
                    if (b != null) {
                        emu.writeMemoryValue(rbufAddr, 2, b & 0xFF);
                        rxDone = true;
                        rxNextAllowed = now + charNanos;
                        csrDirty = true;
                    }
                }

                if (csrDirty) {
                    csrDirty = false;
                    setBit7(emu, xcsrAddr, txReady);
                    setBit7(emu, rcsrAddr, rxDone);
                }

                emu.step(monitor);
            }
        } catch (Exception ex) {
            println("Emulation stopped: " + ex);
        } finally {
            dumpState(emu, "Stopped");
            running = false;
            statusTimer.stop();
            // give TX thread a moment to flush what is already queued
            long flushUntil = System.currentTimeMillis() + 500;
            while (!txQueue.isEmpty() && System.currentTimeMillis() < flushUntil) Thread.sleep(10);
            port.closePort();
            emu.dispose();
            SwingUtilities.invokeLater(frame::dispose);
            println("COM port closed. TX=" + txCount.get() + " RX=" + rxCount.get());
        }
    }

    private void dumpState(EmulatorHelper emu, String prefix) {
        try {
            StringBuilder sb = new StringBuilder(prefix + " @");
            for (String r : new String[] { "PC", "R0", "R1", "R2", "R3", "R4", "R5", "SP", "PS" }) {
                sb.append(String.format(" %s=%04X", r, emu.readRegister(r).longValue() & 0xFFFF));
            }
            for (int a : new int[] { RCSR, RBUF, XCSR, XBUF }) {
                byte[] c = emu.readMemory(toAddr(a), 2);
                sb.append(String.format(" [%04X]=%04X", a, (c[0] & 0xFF) | ((c[1] & 0xFF) << 8)));
            }
            println(sb.toString());
        } catch (Exception e) {
            println("Dump failed: " + e);
        }
    }
}

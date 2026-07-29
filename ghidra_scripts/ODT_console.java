//PDP11 Octal Debugging Technique (ODT) console interface - live trace polling
//@author 
//@category PDP11 Hardware
//@keybinding 
//@menupath 
//@toolbar 
//@runtime Java

import ghidra.app.script.GhidraScript;
import ghidra.debug.flatapi.FlatDebuggerAPI;
import ghidra.trace.model.Trace;
import ghidra.trace.model.memory.TraceMemoryManager;
import ghidra.program.model.address.Address;
import javax.swing.*;
import java.awt.*;
import java.nio.ByteBuffer;

public class ODT_console extends GhidraScript implements FlatDebuggerAPI {

    private volatile boolean windowOpen = true;

    public void run() throws Exception {
        JFrame frame = new JFrame("ODT Console (live, auto-polling)");
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setAlwaysOnTop(true);
        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            public void windowClosed(java.awt.event.WindowEvent e) {
                windowOpen = false;
            }
        });

        JTextArea screen = new JTextArea(15, 40);
        screen.setEditable(false);
        screen.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane screenScroll = new JScrollPane(screen);
        JLabel status = new JLabel("Polling...");

        JPanel content = new JPanel(new BorderLayout());
        content.add(new JLabel("PDP-11 ODT Console (live)", SwingConstants.CENTER), BorderLayout.NORTH);
        content.add(screenScroll, BorderLayout.CENTER);
        content.add(status, BorderLayout.SOUTH);
        frame.setContentPane(content);
        frame.pack();
        frame.setMinimumSize(new Dimension(450, 350));
        frame.setLocationRelativeTo(null);
        SwingUtilities.invokeLater(() -> frame.setVisible(true));

        byte lastXcsr = 0;

        while (windowOpen && !monitor.isCancelled()) {
            try {
                Trace trace = getCurrentTrace();
                if (trace != null) {
                    long snap = getCurrentSnap(); // re-fetched fresh every loop
                    TraceMemoryManager memMgr = trace.getMemoryManager();
                    Address xcsrAddr = trace.getBaseAddressFactory().getAddress("ram:0xFF74");
                    Address xbufAddr = trace.getBaseAddressFactory().getAddress("ram:0xFF76");

                    byte[] xcsrBuf = new byte[1];
                    memMgr.getBytes(snap, xcsrAddr, ByteBuffer.wrap(xcsrBuf));
                    byte xcsrVal = xcsrBuf[0];

                    if ((xcsrVal & 0x80) != 0 && (lastXcsr & 0x80) == 0) {
                        byte[] xbufBuf = new byte[1];
                        memMgr.getBytes(snap, xbufAddr, ByteBuffer.wrap(xbufBuf));
                        char c = (char) (xbufBuf[0] & 0x7F);
                        String toAppend = (c == 7) ? "[BELL]" : String.valueOf(c);
                        SwingUtilities.invokeLater(() -> {
                            screen.append(toAppend);
                            screen.setCaretPosition(screen.getDocument().getLength());
                        });
                        String hex = String.format("Last char: 0x%02X", xbufBuf[0] & 0xFF);
                        SwingUtilities.invokeLater(() -> status.setText(hex));
                    }
                    lastXcsr = xcsrVal;
                } else {
                    SwingUtilities.invokeLater(() -> status.setText("No active trace."));
                }
            } catch (Exception ex) {
                String msg = ex.getMessage();
                SwingUtilities.invokeLater(() -> status.setText("Poll error: " + msg));
            }
            Thread.sleep(250);
        }
    }
}

//PDP11 computer front panel Swithes
//@author 
//@category PDP11 Hardware
//@keybinding 
//@menupath 
//@toolbar 
//@runtime Java
import ghidra.app.script.GhidraScript;
import ghidra.program.model.mem.*;
import ghidra.program.model.address.*;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;

public class SetFrontPanelSwitches extends GhidraScript {
    public void run() throws Exception {
        Address switchAddr = getAddressFactory().getAddress("ram:0xFF78"); // FPANEL_SWITCH
        Memory mem = currentProgram.getMemory();

        JFrame frame = new JFrame("Front Panel Switch Register (FPANEL_SWITCH)");
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setAlwaysOnTop(true);

        JLabel header = new JLabel("PDP-11 Front Panel Switch Register (ram:0xFF78)", SwingConstants.CENTER);

        JCheckBox[] bits = new JCheckBox[16];
        JPanel switchPanel = new JPanel(new GridLayout(16, 1));
        for (int i = 15; i >= 0; i--) {
            bits[i] = new JCheckBox("Bit " + i);
            switchPanel.add(bits[i]);
        }

        JLabel preview = new JLabel(" ", SwingConstants.CENTER);
        JButton writeButton = new JButton("Write to Memory");

        Runnable updatePreview = () -> {
            short value = 0;
            for (int i = 0; i < 16; i++) {
                if (bits[i].isSelected()) {
                    value |= (1 << i);
                }
            }
            int uval = value & 0xFFFF;
            String hex = String.format("%04X", uval);
            String oct = String.format("%06o", uval);
            String octSpaced = oct.substring(0, 3) + " " + oct.substring(3, 6);
            preview.setText(String.format("HEX:%s OCTAL:%s, not written", hex, octSpaced));
        };

        for (int i = 0; i < 16; i++) {
            final int idx = i;
            bits[i].addItemListener(e -> updatePreview.run());
        }

        writeButton.addActionListener(e -> {
            short value = 0;
            for (int i = 0; i < 16; i++) {
                if (bits[i].isSelected()) {
                    value |= (1 << i);
                }
            }
            int uval = value & 0xFFFF;
            try {
                int txId = currentProgram.startTransaction("Set FPANEL_SWITCH");
                try {
                    mem.setShort(switchAddr, value);
                } finally {
                    currentProgram.endTransaction(txId, true);
                }
                String hex = String.format("%04X", uval);
                String oct = String.format("%06o", uval);
                String octSpaced = oct.substring(0, 3) + " " + oct.substring(3, 6);
                preview.setText(String.format("HEX:%s OCTAL:%s, written", hex, octSpaced));
            } catch (Exception ex) {
                preview.setText("Error: " + ex.getMessage());
            }
        });

        updatePreview.run();

        JPanel bottom = new JPanel(new GridLayout(2, 1));
        bottom.add(writeButton);
        bottom.add(preview);

        JPanel content = new JPanel(new BorderLayout());
        content.add(header, BorderLayout.NORTH);
        content.add(switchPanel, BorderLayout.CENTER);
        content.add(bottom, BorderLayout.SOUTH);

        frame.setContentPane(content);
        frame.pack();
        frame.setMinimumSize(new Dimension(320, frame.getHeight()));
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }
}
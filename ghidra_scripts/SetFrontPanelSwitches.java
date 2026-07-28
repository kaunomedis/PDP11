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

public class SetFrontPanelSwitches extends GhidraScript {
    public void run() throws Exception {
        Address switchAddr = getAddressFactory().getAddress("ram:0xFF78"); // FPANEL_SWITCH
        Memory mem = currentProgram.getMemory();

        JFrame frame = new JFrame("Front Panel Switch Register (FPANEL_SWITCH)");
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setAlwaysOnTop(true);

        JCheckBox[] bits = new JCheckBox[16];
        JPanel switchPanel = new JPanel(new GridLayout(16, 1));
        for (int i = 15; i >= 0; i--) {
            bits[i] = new JCheckBox("Bit " + i);
            switchPanel.add(bits[i]);
        }

        JLabel status = new JLabel("Not written yet.");
        JButton writeButton = new JButton("Write to Memory");

        writeButton.addActionListener(e -> {
            short value = 0;
            for (int i = 0; i < 16; i++) {
                if (bits[i].isSelected()) {
                    value |= (1 << i);
                }
            }
            try {
                int txId = currentProgram.startTransaction("Set FPANEL_SWITCH");
                try {
                    mem.setShort(switchAddr, value);
                } finally {
                    currentProgram.endTransaction(txId, true);
                }
                status.setText(String.format("Written: 0x%04X", value & 0xFFFF));
            } catch (Exception ex) {
                status.setText("Error: " + ex.getMessage());
            }
        });

        JPanel content = new JPanel(new BorderLayout());
        content.add(switchPanel, BorderLayout.CENTER);
        content.add(writeButton, BorderLayout.SOUTH);
        content.add(status, BorderLayout.NORTH);

        frame.setContentPane(content);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }
}
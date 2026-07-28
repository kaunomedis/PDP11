//TODO write a description for this script
//@author 
//@category _NEW_
//@keybinding 
//@menupath 
//@toolbar 
//@runtime Java
import ghidra.app.script.GhidraScript;
import ghidra.program.model.lang.protorules.*;
import ghidra.program.model.mem.*;
import ghidra.program.model.lang.*;
import ghidra.program.model.pcode.*;
import ghidra.program.model.data.ISF.*;
import ghidra.program.model.util.*;
import ghidra.program.model.reloc.*;
import ghidra.program.model.data.*;
import ghidra.program.model.block.*;
import ghidra.program.model.symbol.*;
import ghidra.program.model.scalar.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.address.*;
import javax.swing.*;
import java.awt.*;

public class SetFrontPanelSwitches extends GhidraScript {
    public void run() throws Exception {
        Address switchAddr = getAddressFactory().getAddress("ram:0xFF78"); // FPANEL_SWITCH

        JCheckBox[] bits = new JCheckBox[16];
        JPanel panel = new JPanel(new GridLayout(16, 1));
        for (int i = 15; i >= 0; i--) {
            bits[i] = new JCheckBox("Bit " + i);
            panel.add(bits[i]);
        }

        int result = JOptionPane.showConfirmDialog(
            null, panel, "Front Panel Switch Register (FPANEL_SWITCH)",
            JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE
        );

        if (result != JOptionPane.OK_OPTION) {
            println("Cancelled - no change made.");
            return;
        }

        short value = 0;
        for (int i = 0; i < 16; i++) {
            if (bits[i].isSelected()) {
                value |= (1 << i);
            }
        }

        Memory mem = currentProgram.getMemory();
        int transactionId = currentProgram.startTransaction("Set FPANEL_SWITCH");
        try {
            mem.setShort(switchAddr, value);
        } finally {
            currentProgram.endTransaction(transactionId, true);
        }

        println(String.format("FPANEL_SWITCH set to 0x%04X", value & 0xFFFF));
    }
}

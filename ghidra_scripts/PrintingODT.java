//Line printing ODT in emulator
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
public class PrintingODT extends GhidraScript {
    public void run() throws Exception {
        EmulatorHelper emu = new EmulatorHelper(currentProgram);
		String startPCStr = askString("Start PC", "Enter starting PC (hex, no 0x):", "0080");
		long startPC = Long.parseLong(startPCStr.trim(), 16);
		
        emu.writeRegister("PC", startPC);
        emu.writeRegister("PS", 0x0000);
        emu.writeRegister("SP", 0x5000);
		
		println("Starting ODT>:\n\r");
		emu.writeMemoryValue(toAddr(0xFF74), 2, 0xFFFF);
        Address xbufAddr = toAddr(0xFF76);
        emu.getEmulator().addMemoryAccessFilter(new MemoryAccessFilter() {
            @Override
            protected void processRead(AddressSpace space, long offset, int size, byte[] values) {
                // not needed for this script - intentionally left empty
            }
            @Override
            protected void processWrite(AddressSpace space, long offset, int size, byte[] values) {
                Address written = space.getAddress(offset);
                if (written.equals(xbufAddr)) {
                    int b = values[0] & 0xFF;
                    if (b == 0x0A || b == 0x0D || b == 0x09) {
                        print(String.valueOf((char) b));
                    } else if (b < 0x20 || b > 0x7f) {
                        print(String.format("[%02X]", b));
                    } else {
                        print(String.valueOf((char) b));
                    }
                }
            }
        });
        while (!monitor.isCancelled()) {
		
		long pc = emu.readRegister("PC").longValue();
		Instruction instr = currentProgram.getListing().getInstructionAt(toAddr(pc));
        String instrText = (instr != null) ? instr.toString() : "??";
            if (instrText.trim().startsWith("HALT")) {
				
                print("\n\rReached HALT at PC=0x" + Long.toHexString(pc) + " - stopping (like Ghidra's Debugger would).");
                break;
            }	
        emu.step(monitor);
        }
	print("\r\n");
    }
}
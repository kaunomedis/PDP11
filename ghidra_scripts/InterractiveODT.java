//Line Interractive ODT in emulator
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

public class InterractiveODT extends GhidraScript {
    public void run() throws Exception {
        EmulatorHelper emuHelper = new EmulatorHelper(currentProgram);
        String startPCStr = askString("Start PC", "Enter starting PC (hex, no 0x):", "0080");
        long startPC = Long.parseLong(startPCStr.trim(), 16);

        emuHelper.writeRegister("PC", startPC);
        emuHelper.writeRegister("PS", 0x0000);
        emuHelper.writeRegister("SP", 0x5000);

        println("Starting ODT>:\n\r");
        emuHelper.writeMemoryValue(toAddr(0xFF74), 2, 0xFFFF); // XCSR: transmitter always ready

        Address xbufAddr = toAddr(0xFF76);
        Address rbufAddr = toAddr(0xFF72);
        Address rcsrAddr = toAddr(0xFF70);

        emuHelper.writeMemoryValue(rcsrAddr, 2, 0x0000); // RCSR starts "not ready"

        final StringBuilder pending = new StringBuilder();

        emuHelper.getEmulator().addMemoryAccessFilter(new MemoryAccessFilter() {
            @Override
            protected void processRead(AddressSpace space, long offset, int size, byte[] values) {
                Address read = space.getAddress(offset);
                if (read.equals(rbufAddr)) {
                    // Real hardware: reading RBUF clears RCSR's ready bit (bit7)
                    try {
                        byte[] cur = emuHelper.readMemory(rcsrAddr, 2);
                        int val = (cur[0] & 0xFF) | ((cur[1] & 0xFF) << 8);
                        emuHelper.writeMemoryValue(rcsrAddr, 2, val & ~0x80);
                    } catch (Exception e) {
                        // best effort only
                    }
                }
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

            long pc = emuHelper.readRegister("PC").longValue();
            Instruction instr = currentProgram.getListing().getInstructionAt(toAddr(pc));
            String instrText = (instr != null) ? instr.toString() : "??";
            if (instrText.trim().startsWith("HALT")) {
                print("\n\rReached HALT at PC=0x" + Long.toHexString(pc) + " - stopping (like Ghidra's Debugger would).");
                break;
            }

            byte[] rcsrBytes = emuHelper.readMemory(rcsrAddr, 2);
            int rcsrVal = (rcsrBytes[0] & 0xFF) | ((rcsrBytes[1] & 0xFF) << 8);
            boolean ready = (rcsrVal & 0x80) != 0;

            if (!ready) {
                if (pending.length() == 0) {
                    String more = askString("Console Input",
                        "Type text to send to the emulated console (blank = stop sending input):", "");
                    if (more != null && !more.isEmpty()) {
                        pending.append(more).append("\r"); // simulate pressing ENTER
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
        print("\r\n");
    }
}

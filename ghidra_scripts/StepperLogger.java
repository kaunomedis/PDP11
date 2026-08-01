//PDP11 Interactive Stepper Logger
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
import java.io.FileWriter;
import java.io.PrintWriter;

public class StepperLogger extends GhidraScript {
    public void run() throws Exception {

        String startPCStr = askString("Start PC", "Enter starting PC (hex, no 0x):", "1000");
        String startSPStr = askString("Start SP", "Enter starting SP (hex, no 0x):", "5000");
        String stopAtStr  = askString("Stop Address", "Stop when PC reaches (hex, no 0x, or type 'none'):", "none");
        int maxSteps = askInt("Iterations", "Maximum number of steps to run:");
        String watchStr = askString("Watch Addresses",
            "Comma-separated RAM addresses to watch (hex, no 0x):", "17b0,17b2,17ca");
        String logPath = askString("Log File", "Full path to save log file:", "C:/GHIDRA/trace_log.txt");

        long startPC = Long.parseLong(startPCStr.trim(), 16);
        long startSP = Long.parseLong(startSPStr.trim(), 16);
        Long stopAt = stopAtStr.trim().equalsIgnoreCase("none") ? null : Long.parseLong(stopAtStr.trim(), 16);

        String[] watchParts = watchStr.split(",");
        long[] watchAddrs = new long[watchParts.length];
        for (int i = 0; i < watchParts.length; i++) {
            watchAddrs[i] = Long.parseLong(watchParts[i].trim(), 16);
        }

        long xbufAddr = 0xFF76;

        EmulatorHelper emu = new EmulatorHelper(currentProgram);
        emu.writeRegister("PC", startPC);
        emu.writeRegister("SP", startSP);
        emu.writeRegister("PS", 0x0000);

        PrintWriter log = new PrintWriter(new FileWriter(logPath));
        StringBuilder screen = new StringBuilder();
        Address xbufAddrObj = toAddr(xbufAddr);

        emu.getEmulator().addMemoryAccessFilter(new MemoryAccessFilter() {
            @Override
            protected void processRead(AddressSpace space, long offset, int size, byte[] values) {}

            @Override
            protected void processWrite(AddressSpace space, long offset, int size, byte[] values) {
                if (space.getAddress(offset).equals(xbufAddrObj)) {
                    screen.append((char) (values[0] & 0xFF));
                }
            }
        });

        for (int step = 0; step < maxSteps; step++) {
            long pc = emu.readRegister("PC").longValue();
            if (stopAt != null && pc == stopAt) {
                log.println("Reached stop address 0x" + Long.toHexString(stopAt) + " after " + step + " steps.");
                break;
            }

            Instruction instr = currentProgram.getListing().getInstructionAt(toAddr(pc));
            String instrText = (instr != null) ? instr.toString() : "??";

            StringBuilder line = new StringBuilder();
            line.append(String.format("[%3d] PC=%04X  %-28s", step, pc, instrText));
            line.append(String.format(" R0=%04X R1=%04X R2=%04X R3=%04X R4=%04X R5=%04X SP=%04X PS=%04X",
                emu.readRegister("R0").longValue() & 0xFFFF,
                emu.readRegister("R1").longValue() & 0xFFFF,
                emu.readRegister("R2").longValue() & 0xFFFF,
                emu.readRegister("R3").longValue() & 0xFFFF,
                emu.readRegister("R4").longValue() & 0xFFFF,
                emu.readRegister("R5").longValue() & 0xFFFF,
                emu.readRegister("SP").longValue() & 0xFFFF,
                emu.readRegister("PS").longValue() & 0xFFFF
            ));
            line.append(" | ");
            for (long addr : watchAddrs) {
                byte[] b = emu.readMemory(toAddr(addr), 2);
                int val = (b[0] & 0xFF) | ((b[1] & 0xFF) << 8);
                line.append(String.format("[%04X]=%04X ", addr, val));
            }
            line.append(" | SCREEN=\"" + screen.toString() + "\"");

            println(line.toString());
            log.println(line.toString());

            if (instrText.trim().startsWith("HALT")) {
                log.println("Reached HALT at PC=0x" + Long.toHexString(pc) + " after " + step + " steps - stopping (like Ghidra's Debugger would).");
                break;
            }

            boolean ok = emu.step(monitor);
            if (!ok) {
                log.println("Emulation stopped: " + emu.getLastError());
                break;
            }
        }

        log.close();
        println("Done. Log written to: " + logPath);
    }
}
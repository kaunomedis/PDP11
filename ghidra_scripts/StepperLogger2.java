//PDP11 Interactive Stepper Logger
//@author 
//@category PDP11 Hardware
//@keybinding 
//@menupath 
//@toolbar world.png
//@runtime Java

import ghidra.app.script.GhidraScript;
import ghidra.app.emulator.EmulatorHelper;
import ghidra.app.emulator.MemoryAccessFilter;
import ghidra.program.model.address.Address;
import ghidra.program.model.address.AddressSpace;
import ghidra.program.model.listing.Instruction;
import java.io.FileWriter;
import java.io.PrintWriter;

public class StepperLogger2 extends GhidraScript {
    public void run() throws Exception {

        String startPCStr = askString("Start PC", "Enter starting PC (hex, no 0x):", "1000");
        String startSPStr = askString("Start SP", "Enter starting SP (hex, no 0x):", "5000");
        String stopAtStr  = askString("Stop Address", "Stop when PC reaches (hex, no 0x, or type 'none'):", "none");
        int maxSteps = askInt("Iterations", "Maximum number of steps to run:");
        String watchStr = askString("Watch Addresses",
            "Comma-separated RAM addresses to watch (hex, no 0x):", "FF76");
        String logPath = askString("Log File", "Full path to save log file:", "C:/GHIDRA/trace_log.txt");
		String scrPath = askString("Log File for screen", "Full path to save log file:", "C:/GHIDRA/trace_scr.txt");

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
		PrintWriter log2 = new PrintWriter(new FileWriter(scrPath));
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
				if (space.getName().equals("ram") && offset >= 0x1ef0 && offset <= 0x3fff) {
                    log.println(String.format("WRITE TRAP: wrote 0x%04X to address 0x%04X",
                        values[0] & 0xFF | ((values[1] & 0xFF) << 8), offset));
                    log.flush();
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
            //line.append(" | SCREEN=\"" + screen.toString() + "\"");

            //println(line.toString());
            log.println(line.toString());
			
			log2.print(screen.toString());
			screen.setLength(0);

            if (instrText.trim().startsWith("HALT")) {
                log.println("Reached HALT at PC=0x" + Long.toHexString(pc) + " after " + step + " steps - stopping (like Ghidra's Debugger would).");
                break;
            }



// Clear RCSR ready bit if the just-executed instruction read RBUF
// (approximation - real clear-on-read needs a MemoryAccessFilter;
// this stepper doesn't have one, so we clear it here based on whether
// the instruction we just stepped past was an RBUF-reading MOVB/MOV)
if (instrText.contains("0xff72")) {
    emu.writeMemoryValue(toAddr(0xFF70), 2, 0x0000);
}

// Inject test input whenever RCSR isn't ready
byte[] rcsrCheck = emu.readMemory(toAddr(0xFF70), 2);
int rcsrVal = (rcsrCheck[0] & 0xFF) | ((rcsrCheck[1] & 0xFF) << 8);
if ((rcsrVal & 0x80) == 0) {


	String rpc = " PC="+Long.toHexString(emu.readRegister("PC").longValue());
    String key = askString("Key Input @"+rpc, "Character to inject into RBUF (EXIT=exit trace, ^O =Cntr+O, ^[ =Esc):", "");

	log2.println("\n\r< request for key input @"+rpc+", injected: '"+key+"' >");


	if (key.length()>1 && key.charAt(0) == '^'){
			int next = Character.toUpperCase(key.charAt(1));
			emu.writeMemoryValue(toAddr(0xFF72), 2, (long) next & 0x1F);
			emu.writeMemoryValue(toAddr(0xFF70), 2, 0x80);
	}
	else if (new String("EXIT").equals(key)){break;}
    else if (key != null && !key.isEmpty()) {
			emu.writeMemoryValue(toAddr(0xFF72), 2, (long) key.charAt(0) & 0xFF);
			emu.writeMemoryValue(toAddr(0xFF70), 2, 0x80);
    }
}








            boolean ok = emu.step(monitor);
            if (!ok) {
                log.println("Emulation stopped: " + emu.getLastError());
				
				
				final int LINES_BEFORE = 16;
				final int LINES_AFTER=2;
				
				pc=(emu.readRegister("PC").longValue() & 0xFFFF)-16*LINES_BEFORE;
				pc=pc & 0xFFF0;
				byte[] b = emu.readMemory(toAddr(pc), 16*(LINES_BEFORE+LINES_AFTER+1));
				String ramas=" RAM: \n";
				for(int j=0;j<(LINES_BEFORE+LINES_AFTER+1)*16;j=j+16){
				ramas=ramas+String.format("%04X:",pc+j);
				for(int i=0;i<16;i++) {
				int val = (b[i+j] & 0xFF);
				ramas=ramas+String.format(" %02X", val);
				}
				ramas=ramas+"\n";
				}
				log.println(ramas);
				
                break;
            }
        }

        log.close();
		log2.close();
        println("Done. Log written to: " + logPath+" and "+scrPath);
		// Cleanup resources and release hold on currentProgram
		emu.dispose();
    }
}
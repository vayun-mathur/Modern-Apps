package com.vayunmathur.games.logicgate.data

/**
 * Chip library with multi-bit BUS support.
 *
 * Wire widths supported: 1-bit (thin green), 4-bit (orange nibble bus), 8-bit (thick blue byte bus),
 * plus 2/3/6 for address decode shortcuts. User can work with individual bit wires OR bus wires.
 *
 * Two zero-cost converters make big levels LESS tedious (you wanted expanders & contractors):
 *   JOIN_8 = Expander: 8×1b → BUS8[8] thick – compresses 8 thin into one thick wire (expander)
 *   SPLIT_8 = Contractor: BUS8[8] → 8×1b – expands one thick back into bits (contractor)
 *   Same for 4-bit: JOIN_4 (expander 4 bits → BUS4[4]) and SPLIT_4 (contractor BUS4→bits)
 *   Plus bus MUXes: MUX_B8, MUX4_B8, MUX8_B8, DMUX_B8, DMUX8_B – byte-wide routing with one wire.
 *
 * Toward stored-program computer (runs programs FROM RAM, von Neumann):
 *   Need: SR_LATCH (first 1-bit hold via NOR feedback), DFF edge, BIT (MUX+DFF load), REG_8B bus accumulator,
 *         address decode DMUX4/DMUX8 / DMUX8_B bus, INC_8 bus (PC=PC+1), RAM_8B 8×8 → RAM_64B 64×8 → RAM_256B 256×8 main memory unified prog+data,
 *         PC_8 bus instruction fetch pointer, ALU_8 bus with zero flag for JZ, CPU bus fetch-decode-execute FROM RAM, COMPUTER final wiring.
 */

data class ChipDef(
    val id: String,
    val displayName: String,
    val inputs: List<String>,
    val outputs: List<String>,
    val nandCost: Int,
    val description: String,
    val category: ChipCategory,
    val inputWidths: List<Int> = inputs.map { 1 },
    val outputWidths: List<Int> = outputs.map { 1 },
    val eval: (List<Boolean>) -> List<Boolean>
) {
    val inputCount: Int get() = inputs.size
    val outputCount: Int get() = outputs.size
    val totalInputBits: Int get() = inputWidths.sum()
    val totalOutputBits: Int get() = outputWidths.sum()
    fun inputPinBitOffset(pin: Int): Int = inputWidths.take(pin).sum()
    fun outputPinBitOffset(pin: Int): Int = outputWidths.take(pin).sum()
    fun inputPinWidth(pin: Int): Int = inputWidths.getOrElse(pin) { 1 }
    fun outputPinWidth(pin: Int): Int = outputWidths.getOrElse(pin) { 1 }
    fun inputPinLabel(pin: Int): String { val w=inputPinWidth(pin); return if(w==1) inputs[pin] else "${inputs[pin]}[${w}]" }
    fun outputPinLabel(pin: Int): String { val w=outputPinWidth(pin); return if(w==1) outputs[pin] else "${outputs[pin]}[$w]" }
}

enum class ChipCategory { PRIMITIVE, FOUNDATION, ROUTING, ARITH, MEMORY, CPU, BUS }

object ChipLibrary {
    // PRIMITIVE / FOUNDATION – 1-bit
    val NAND = ChipDef("NAND","NAND", listOf("a","b"), listOf("out"),1,"Primitive NOT(AND)",ChipCategory.PRIMITIVE, eval={ inp-> listOf(!(inp[0] && inp[1])) })
    val NOT = ChipDef("NOT","NOT", listOf("in"), listOf("out"),1,"Inverter NAND(in,in)",ChipCategory.FOUNDATION, eval={ inp-> listOf(!inp[0]) })
    val AND = ChipDef("AND","AND", listOf("a","b"), listOf("out"),2,"AND",ChipCategory.FOUNDATION, eval={ inp-> listOf(inp[0] && inp[1]) })
    val OR = ChipDef("OR","OR", listOf("a","b"), listOf("out"),3,"OR",ChipCategory.FOUNDATION, eval={ inp-> listOf(inp[0] || inp[1]) })
    val XOR = ChipDef("XOR","XOR", listOf("a","b"), listOf("out"),4,"XOR key for adder",ChipCategory.FOUNDATION, eval={ inp-> listOf(inp[0] xor inp[1]) })
    val NOR = ChipDef("NOR","NOR", listOf("a","b"), listOf("out"),4,"NOR – cross couple for SR latch",ChipCategory.FOUNDATION, eval={ inp-> listOf(!(inp[0] || inp[1])) })
    val XNOR = ChipDef("XNOR","XNOR", listOf("a","b"), listOf("out"),5,"XNOR equality",ChipCategory.FOUNDATION, eval={ inp-> listOf(!(inp[0] xor inp[1])) })

    // ROUTING bit – address decode blocks needed for RAM
    val MUX = ChipDef("MUX","MUX", listOf("a","b","sel"), listOf("out"),4,"sel?b:a",ChipCategory.ROUTING, eval={ inp-> listOf(if(inp[2]) inp[1] else inp[0]) })
    val DMUX = ChipDef("DMUX","DMUX", listOf("in","sel"), listOf("a","b"),3,"Route in→a|b",ChipCategory.ROUTING, eval={ inp-> val inn=inp[0]; val sel=inp[1]; if(sel) listOf(false,inn) else listOf(inn,false) })
    val MUX4 = ChipDef("MUX4","MUX4", listOf("a","b","c","d","sel0","sel1"), listOf("out"),12,"4-way mux sel1/0",ChipCategory.ROUTING, eval={ inp-> val sel=(if(inp[5])2 else 0)+(if(inp[4])1 else 0); listOf(inp[sel]) })
    val DMUX4 = ChipDef("DMUX4","DMUX4", listOf("in","sel0","sel1"), listOf("a","b","c","d"),8,"DMUX4 address→cell – RAM decode core",ChipCategory.ROUTING, eval={ inp-> val inn=inp[0]; val sel=(if(inp[2])2 else 0)+(if(inp[1])1 else 0); List(4){i-> i==sel && inn} })
    val MUX8 = ChipDef("MUX8","MUX8", listOf("a","b","c","d","e","f","g","h","sel0","sel1","sel2"), listOf("out"),24,"8-way mux – reading RAM",ChipCategory.ROUTING, eval={ inp-> val sel=(if(inp[10])4 else 0)+(if(inp[9])2 else 0)+(if(inp[8])1 else 0); listOf(inp[sel]) })
    val DMUX8 = ChipDef("DMUX8","DMUX8", listOf("in","sel0","sel1","sel2"), listOf("a","b","c","d","e","f","g","h"),16,"8-way demux – write enable for RAM – one addr to one reg",ChipCategory.ROUTING, eval={ inp-> val inn=inp[0]; val sel=(if(inp[3])4 else 0)+(if(inp[2])2 else 0)+(if(inp[1])1 else 0); List(8){i-> i==sel && inn} })

    // BUS converters – expanders (JOIN = bits→bus thick) / contractors (SPLIT = bus → bits) – cost 0 to cut tedium
    val JOIN_8 = ChipDef("JOIN_8","JOIN8 Expander 8", (0..7).map{"b$it"}, listOf("OUT"),0,"EXPANDER: 8 bits → BUS8[8] thick blue wire – one wire instead of 8 thin",ChipCategory.BUS, inputWidths=List(8){1}, outputWidths=listOf(8), eval={ inp-> inp.take(8) })
    val SPLIT_8 = ChipDef("SPLIT_8","SPLIT8 Contractor 8", listOf("IN"), (0..7).map{"b$it"},0,"CONTRACTOR: BUS8[8] → 8 bits – split thick blue for bit logic",ChipCategory.BUS, inputWidths=listOf(8), outputWidths=List(8){1}, eval={ inp-> inp.take(8) })
    val JOIN_4 = ChipDef("JOIN_4","JOIN4 Exp4", (0..3).map{"b$it"}, listOf("OUT"),0,"EXPANDER: 4 bits→BUS4[4] orange – opcode nibble – less tedious",ChipCategory.BUS, inputWidths=List(4){1}, outputWidths=listOf(4), eval={ inp-> inp.take(4) })
    val SPLIT_4 = ChipDef("SPLIT_4","SPLIT4 Cont4", listOf("IN"), (0..3).map{"b$it"},0,"CONTRACTOR: BUS4[4] → 4 bits – decode opcode bus",ChipCategory.BUS, inputWidths=listOf(4), outputWidths=List(4){1}, eval={ inp-> inp.take(4) })

    // Bus MUXes – one thick wire carries full byte, drastically less wiring
    val MUX_B8 = ChipDef("MUX_B8","MUX B8 [8]", listOf("A","B","sel"), listOf("OUT"),32,"Bus MUX: A[8],B[8],sel→OUT[8] – one wire carries byte (less tedious) – needed for CPU address mux PC vs data addr",ChipCategory.BUS, inputWidths=listOf(8,8,1), outputWidths=listOf(8), eval={ inp-> val a=inp.slice(0..7); val b=inp.slice(8..15); val sel=inp[16]; if(sel) b else a })
    val DMUX_B8 = ChipDef("DMUX_B8","DMUX B8", listOf("IN","sel"), listOf("A","B"),24,"Bus DMUX: IN[8],sel→A[8],B[8]",ChipCategory.BUS, inputWidths=listOf(8,1), outputWidths=listOf(8,8), eval={ inp-> val inn=inp.slice(0..7); val sel=inp[8]; if(sel) List(8){false}+inn else inn+List(8){false} })
    val MUX4_B8 = ChipDef("MUX4_B8","MUX4 B8 [8]", listOf("A","B","C","D","sel0","sel1"), listOf("OUT"),96,"4-way BUS8 mux – RAM read path – A-D[8] bus",ChipCategory.BUS, inputWidths=listOf(8,8,8,8,1,1), outputWidths=listOf(8), eval={ inp-> val sel=(if(inp[33])2 else 0)+(if(inp[32])1 else 0); inp.slice(sel*8 until sel*8+8) })
    val MUX8_B8 = ChipDef("MUX8_B8","MUX8 B8 [8]", listOf("A","B","C","D","E","F","G","H","sel0","sel1","sel2"), listOf("OUT"),192,"8-way BUS8 – 8×BUS8→BUS8 bus building RAM64/256 read – one wire",ChipCategory.BUS, inputWidths=listOf(8,8,8,8,8,8,8,8,1,1,1), outputWidths=listOf(8), eval={ inp-> val sel=(if(inp[66])4 else 0)+(if(inp[65])2 else 0)+(if(inp[64])1 else 0); inp.slice(sel*8 until sel*8+8) })
    val DMUX8_B = ChipDef("DMUX8_B","DMUX8 B [8]", listOf("IN","sel0","sel1","sel2"), List(8){"O$it"},128,"Bus DMUX8: IN[8]→one of 8 BUS8 – RAM write path thick",ChipCategory.BUS, inputWidths=listOf(8,1,1,1), outputWidths=List(8){8}, eval={ inp-> val inn=inp.slice(0..7); val sel=(if(inp[10])4 else 0)+(if(inp[9])2 else 0)+(if(inp[8])1 else 0); val out=MutableList(64){false}; for(k in 0..7) out[sel*8+k]=inn[k]; out })

    // ARITH
    val HALF_ADDER = ChipDef("HALF_ADDER","HalfAdder", listOf("a","b"), listOf("sum","carry"),6,"sum=a^b carry=a&b",ChipCategory.ARITH, eval={ inp-> listOf(inp[0] xor inp[1], inp[0] && inp[1]) })
    val FULL_ADDER = ChipDef("FULL_ADDER","FullAdder", listOf("a","b","cin"), listOf("sum","cout"),14,"Full adder with carry",ChipCategory.ARITH, eval={ inp-> val a=inp[0]; val b=inp[1]; val cin=inp[2]; listOf(a xor b xor cin, (a&&b)||(b&&cin)||(a&&cin)) })
    val ADDER_4 = ChipDef("ADDER_4","Adder4 bit", listOf("a0","a1","a2","a3","b0","b1","b2","b3"), listOf("s0","s1","s2","s3","cout"),56,"4b ripple adder bit version (tedious)",ChipCategory.ARITH, eval={ inp-> val a=bitsToInt(inp.slice(0..3)); val b=bitsToInt(inp.slice(4..7)); val s=a+b; intToBits(s,4)+listOf(s>=16)})
    val ADDER_8 = ChipDef("ADDER_8","Adder8 bit", (0..7).map{"a$it"}+(0..7).map{"b$it"}, (0..7).map{"s$it"}+listOf("cout"),112,"8b adder bit version",ChipCategory.ARITH, eval={ inp-> val a=bitsToInt(inp.slice(0..7)); val b=bitsToInt(inp.slice(8..15)); val s=a+b; intToBits(s,8)+listOf(s>=256)})
    // Bus versions – drastically less wiring tedium (you said "makes some levels less you know")
    val ADDER_4B = ChipDef("ADDER_4B","Adder4 B [4]", listOf("A","B"), listOf("S","cout"),56,"A[4]+B[4]→S[4] bus – one orange thick [4] wire NOT 8 thin (less tedious)",ChipCategory.ARITH, inputWidths=listOf(4,4), outputWidths=listOf(4,1), eval={ inp-> val a=bitsToInt(inp.slice(0..3)); val b=bitsToInt(inp.slice(4..7)); val s=a+b; intToBits(s,4)+listOf(s>=16)})
    val ADDER_8B = ChipDef("ADDER_8B","Adder8 B [8]", listOf("A","B"), listOf("S","cout"),112,"A[8]+B[8]→S[8] bus – one blue thick [8] wire instead of 16 thin green (less tedious)",ChipCategory.ARITH, inputWidths=listOf(8,8), outputWidths=listOf(8,1), eval={ inp-> val a=bitsToInt(inp.slice(0..7)); val b=bitsToInt(inp.slice(8..15)); val s=a+b; intToBits(s,8)+listOf(s>=256)})

    // INC_8 is incrementer – core for PC = PC+1 (needed to run programs from RAM)
    val INC_8 = ChipDef("INC_8","INC8 B [8]", listOf("A"), listOf("S","cout"),40,"Incrementer bus: A[8] → A+1 – required component for PC",ChipCategory.ARITH, inputWidths=listOf(8), outputWidths=listOf(8,1), eval={ inp-> val a=bitsToInt(inp.take(8)); val s=a+1; intToBits(s,8)+listOf(s>=256)})

    // SUB_8 subtractor for ALU
    val SUB_8 = ChipDef("SUB_8","SUB8 B [8]", listOf("A","B"), listOf("S","borrow"),120,"Subtractor bus A[8]-B[8]→S[8] – ALU op needed",ChipCategory.ARITH, inputWidths=listOf(8,8), outputWidths=listOf(8,1), eval={ inp-> val a=bitsToInt(inp.slice(0..7)); val b=bitsToInt(inp.slice(8..15)); val d=a-b; intToBits(d and 0xFF,8)+listOf(d<0)})

    // MEMORY – toward stored-program machine
    // What you REALLY need to run programs FROM RAM (von Neumann, not Harvard)
    // SR latch = cross-coupled NOR is first 1-bit memory (hold via feedback loop) – must build before DFF
    // DFF edge-triggered → BIT (MUX+DFF + load) → REG_8B bus (8×BIT, common load) = accumulator A
    // RAM = address decoder (DMUX8_B bus) + register bank + read mux (MUX8_B8 bus) – thick blue data bus reduces tedium
    // PC = REG_8B + INC_8 loop, reset=0 load=IN inc=PC+1
    // RAM_256B = unified 256×8 main memory – program bytes AND data bytes share same address space – this is von Neumann key
    val SR_LATCH = ChipDef("SR_LATCH","SRLatch", listOf("S","R"), listOf("Q","nQ"),4,"SR latch cross-coupled NOR – first memory hold via feedback loop",ChipCategory.MEMORY, eval={ inp-> val s=inp[0]; val r=inp[1]; when{ r && !s->listOf(false,true); s && !r->listOf(true,false); else->listOf(false,false)}})
    val DFF = ChipDef("DFF","DFF", listOf("d","clk"), listOf("q"),6,"D flip-flop – rising edge captures D – sequential primitive",ChipCategory.MEMORY, eval={ inp-> listOf(inp[0]) })
    val BIT = ChipDef("BIT","Bit", listOf("in","load"), listOf("out"),12,"1-bit register MUX+DFF – loadable hold",ChipCategory.MEMORY, eval={ inp-> listOf(inp[0]) })
    val REG_8 = ChipDef("REG_8","Reg8 bit", (0..7).map{"in$it"}+listOf("load"), (0..7).map{"out$it"},96,"8-bit register legacy bit (tedious 8 wires)",ChipCategory.MEMORY, eval={ inp-> inp.slice(0..7) })
    val REG_8B = ChipDef("REG_8B","Reg8 B [8]", listOf("IN","load"), listOf("OUT"),96,"Bus Reg: IN[8],load→OUT[8] – accumulator A for CPU – one blue thick wire",ChipCategory.MEMORY, inputWidths=listOf(8,1), outputWidths=listOf(8), eval={ inp-> inp.slice(0..7) })
    val RAM_8 = ChipDef("RAM_8","RAM8 bit", (0..7).map{"in$it"}+listOf("load")+(0..2).map{"a$it"}, (0..7).map{"out$it"},400,"RAM8 legacy bit 8 addrs of 8b",ChipCategory.MEMORY, eval={ inp-> inp.slice(0..7) })
    val RAM_8B = ChipDef("RAM_8B","RAM8 B [8]", listOf("IN","load","ADDR"), listOf("OUT"),400,"RAM8 bus: IN[8],load,ADDR[3]→OUT[8] – small program memory using bus",ChipCategory.MEMORY, inputWidths=listOf(8,1,3), outputWidths=listOf(8), eval={ inp-> inp.slice(0..7) })
    val RAM_64 = ChipDef("RAM_64","RAM64 bit", (0..7).map{"in$it"}+listOf("load")+(0..5).map{"a$it"}, (0..7).map{"out$it"},3200,"RAM64 legacy bit",ChipCategory.MEMORY, eval={ inp-> inp.slice(0..7) })
    val RAM_64B = ChipDef("RAM_64B","RAM64 B [8]", listOf("IN","load","ADDR"), listOf("OUT"),3200,"RAM64 bus 64×8 ADDR[6] bit – data memory for programs",ChipCategory.MEMORY, inputWidths=listOf(8,1,6), outputWidths=listOf(8), eval={ inp-> inp.slice(0..7) })
    val PC_8 = ChipDef("PC_8","PC Bus [8]", listOf("IN","inc","load","reset"), listOf("OUT"),120,"Program Counter bus: reset=0,load=IN[8],inc=PC+1 via INC_8 – instruction fetch pointer – tells RAM which instruction to run – required for stored-program",ChipCategory.MEMORY, inputWidths=listOf(8,1,1,1), outputWidths=listOf(8), eval={ inp-> inp.slice(0..7) })
    val RAM_256 = ChipDef("RAM_256","RAM256 bit", (0..7).map{"in$it"}+listOf("load")+(0..7).map{"a$it"}, (0..7).map{"out$it"},12000,"RAM256 legacy bit",ChipCategory.MEMORY, eval={ inp-> inp.slice(0..7) })
    val RAM_256B = ChipDef("RAM_256B","RAM256 Main [8]", listOf("IN","load","ADDR"), listOf("OUT"),12000,"256×8 main memory BUS von Neumann unified program+data share SAME RAM address space – this enables computer run programs FROM RAM",ChipCategory.MEMORY, inputWidths=listOf(8,1,8), outputWidths=listOf(8), eval={ inp-> inp.slice(0..7) })

    // CPU – fetch/decode/execute that actually uses RAM as program memory
    // ISA: 4-bit opcode via BUS4 orange (SPLIT_4 contractor decodes) + 8-bit addr/imm BUS8 blue
    // Example LDA: A=RAM[addr], STA: RAM[addr]=A, ADD, SUB, JMP unconditional, JZ needs zero flag from ALU_8, LDI load imm, HLT halt
    // CPU internal: A=REG_8B, PC=PC_8 (needs INC_8), ALU=ALU_8 bus, control = DMUX4/JOIN_4 opcode decoder, address mux MUX_B8 (PC[8] vs ADDR_M[8])
    val ALU_8 = ChipDef(
        "ALU_8","ALU Bus [8]", listOf("A","B","OP"), listOf("OUT","zero","carry","neg"),
        300,"ALU bus: OP[3] 000 ADD 001 SUB 010 AND 011 OR 100 XOR 101 NOT 110 INC 111 DEC + flags zero for JZ, carry, neg",
        ChipCategory.CPU, inputWidths=listOf(8,8,3), outputWidths=listOf(8,1,1,1),
        eval={ inp ->
            val a=bitsToInt(inp.slice(0..7)); val b=bitsToInt(inp.slice(8..15))
            val op=(if(inp[18])4 else 0)+(if(inp[17])2 else 0)+(if(inp[16])1 else 0)
            val (res,carry)=when(op){0->{val s=a+b;(s and 0xFF) to (s>=256)};1->{val d=a-b;(d and 0xFF) to (d<0)};2-> (a and b) to false;3->(a or b) to false;4->(a xor b) to false;5->(a.inv() and 0xFF) to false;6->{val s=a+1;(s and 0xFF) to (s>=256)};else->{val d=a-1;(d and 0xFF) to (d<0)} }
            intToBits(res,8)+listOf(res==0,carry,(res and 0x80)!=0)
        }
    )
    val CPU = ChipDef(
        "CPU","CPU Bus [4][8]",
        listOf("OPCODE","ADDR","DATA","reset"), listOf("OUT","writeM","ADDR_M","PC"),
        2000,
        "CPU accumulator fetch-decode-execute FROM RAM: OPCODE[4] orange bus via SPLIT_4/JOIN_4 decoder, ADDR[8] blue bus, DATA[8] blue from main memory RAM_256B, reset. ISA: LDA,STA,ADD,SUB,JMP,JZ(needs ALU zero flag),LDI,HLT. Contains: A=REG_8B accumulator, PC=PC_8 + INC_8, ALU=ALU_8 bus, control decoder DMUX4/DMUX8, address mux MUX_B8 A=PC[8] fetch vs B=ADDR_M[8] data – program lives in RAM (von Neumann).",
        ChipCategory.CPU, inputWidths=listOf(4,8,8,1), outputWidths=listOf(8,1,8,8),
        eval={ _ -> List(25){false} }
    )
    val CPU_8 = ChipDef("CPU_8","CPU compat", CPU.inputs, CPU.outputs, CPU.nandCost, CPU.description, ChipCategory.CPU, inputWidths=CPU.inputWidths, outputWidths=CPU.outputWidths, eval=CPU.eval)
    val COMPUTER = ChipDef(
        "COMPUTER","Computer from RAM [8]", listOf("reset"), listOf("OUT"),15000,
        "Final von Neumann Computer: CPU + RAM_256B Main Memory unified + MUX_B8 address mux + JOIN/SPLIT expander/contractor buses [8][4]. Program stored in RAM same as data (not separate ROM). Example program burned into RAM: data mem[16]=5,17=3, prog @0: LDA16 ADD17 STA18 HLT → RAM[18]=8 → OUT[8]=8. Must wire MUX_B8 A=PC[8] for fetch phase, B=ADDR_M[8] for data phase, MUX OUT[8]→RAM ADDR[8] bus, RAM OUT[8]→CPU DATA[8] bus and via SPLIT_4→OPCODE[4] orange. CPU OUT[8]→RAM IN[8] bus. Use JOIN_8 expander for final output bus. Thickness: thin green 1b, orange 4b nibble, thick blue 8b byte.",
        ChipCategory.CPU, inputWidths=listOf(1), outputWidths=listOf(8), eval={ _ -> intToBits(8,8) }
    )

    val all: Map<String, ChipDef> = listOf(
        NAND,NOT,AND,OR,XOR,NOR,XNOR,
        MUX,DMUX,MUX4,DMUX4,MUX8,DMUX8,
        JOIN_8,SPLIT_8,JOIN_4,SPLIT_4,MUX_B8,DMUX_B8,MUX4_B8,MUX8_B8,DMUX8_B,
        HALF_ADDER,FULL_ADDER,ADDER_4,ADDER_8,ADDER_4B,ADDER_8B,INC_8,SUB_8,
        SR_LATCH,DFF,BIT,REG_8,REG_8B,RAM_8,RAM_8B,RAM_64,RAM_64B,PC_8,RAM_256,RAM_256B,
        ALU_8,CPU,CPU_8,COMPUTER
    ).associateBy{ it.id }

    fun get(id: String): ChipDef = all[id] ?: when(id){
        "JOIN8"->JOIN_8; "SPLIT8"->SPLIT_8; "JOIN4"->JOIN_4; "SPLIT4"->SPLIT_4
        "EXPANDER_8"->JOIN_8; "CONTRACTOR_8"->SPLIT_8; "EXPANDER_4"->JOIN_4; "CONTRACTOR_4"->SPLIT_4
        "MUX_B8"->MUX_B8; "DMUX_B8"->DMUX_B8; "MUX4_B8"->MUX4_B8; "MUX8_B8"->MUX8_B8; "DMUX8_B"->DMUX8_B
        "MUX_B"->MUX_B8
        else-> error("Unknown chip $id – did you mean JOIN_8 expander or SPLIT_8 contractor?")
    }

    fun bitsToInt(bits: List<Boolean>): Int { var v=0; bits.forEachIndexed{i,b-> if(b) v=v or (1 shl i)}; return v }
    fun intToBits(value: Int, width: Int): List<Boolean> = List(width){ i-> (value shr i) and 1==1 }
}
KT
cat games/logicgate/src/main/java/com/vayunmathur/games/logicgate/data/ChipLibrary.kt | wc -l

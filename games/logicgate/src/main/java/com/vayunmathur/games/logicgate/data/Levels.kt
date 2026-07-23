package com.vayunmathur.games.logicgate.data

/**
 * NAND -> Computer (von Neumann stored-program) progression – 36 levels.
 * FOUNDATION 6, ROUTING+BUS 12, ARITH 6, MEMORY 8, CPU 4 = 36 total.
 * Goal: computer runs programs FROM RAM (same 256x8 holds program bytes + data).
 */

enum class ChapterId { FOUNDATION, ROUTING, ARITH, MEMORY, CPU }

data class Chapter(val id: ChapterId, val name: String, val desc: String, val levelIds: List<String>)

data class LevelDef(
    val id: String,
    val displayName: String,
    val chapter: ChapterId,
    val description: String,
    val hint: String,
    val inputs: List<String>,
    val inputWidths: List<Int> = inputs.map { 1 },
    val outputs: List<String>,
    val outputWidths: List<Int> = outputs.map { 1 },
    val targetChipId: String,
    val allowedChipIds: List<String>,
    val flavor: String = "",
    val unlocksChipId: String? = null,
    val prereqs: List<String> = emptyList()
) {
    val totalInputBits: Int get() = inputWidths.sum()
    val totalOutputBits: Int get() = outputWidths.sum()
    fun inputWidth(i: Int): Int = inputWidths.getOrElse(i) { 1 }
    fun outputWidth(i: Int): Int = outputWidths.getOrElse(i) { 1 }
    fun inputBitOffset(i: Int): Int = inputWidths.take(i).sum()
    fun outputBitOffset(i: Int): Int = outputWidths.take(i).sum()
}

object Levels {
    val all: List<LevelDef> = listOf(
        // FOUNDATION 6
        LevelDef("NOT","NOT",ChapterId.FOUNDATION,"Build NOT from NAND only","NAND(in,in)=NOT",listOf("in"),outputs=listOf("out"),targetChipId="NOT",allowedChipIds=listOf("NAND"),flavor="Everything starts with NAND",unlocksChipId="NOT"),
        LevelDef("AND","AND",ChapterId.FOUNDATION,"Build AND: a AND b","NOT(NAND(a,b))",listOf("a","b"),outputs=listOf("out"),targetChipId="AND",allowedChipIds=listOf("NAND","NOT"),unlocksChipId="AND",prereqs=listOf("NOT")),
        LevelDef("OR","OR",ChapterId.FOUNDATION,"Build OR: a OR b","NAND(NOT a, NOT b)",listOf("a","b"),outputs=listOf("out"),targetChipId="OR",allowedChipIds=listOf("NAND","NOT","AND"),flavor="De Morgan",unlocksChipId="OR",prereqs=listOf("AND")),
        LevelDef("XOR","XOR",ChapterId.FOUNDATION,"XOR a^b – adder brick","(a&!b)|(!a&b)",listOf("a","b"),outputs=listOf("out"),targetChipId="XOR",allowedChipIds=listOf("NAND","NOT","AND","OR"),flavor="Key to addition",unlocksChipId="XOR",prereqs=listOf("OR")),
        LevelDef("NOR","NOR",ChapterId.FOUNDATION,"NOR !(a|b) – cross for SR latch","OR+NOT",listOf("a","b"),outputs=listOf("out"),targetChipId="NOR",allowedChipIds=listOf("NAND","NOT","AND","OR","XOR"),flavor="NOR->SR latch first memory",unlocksChipId="NOR",prereqs=listOf("XOR")),
        LevelDef("XNOR","XNOR",ChapterId.FOUNDATION,"XNOR equality","NOT XOR",listOf("a","b"),outputs=listOf("out"),targetChipId="XNOR",allowedChipIds=listOf("NAND","NOT","AND","OR","XOR","NOR"),unlocksChipId="XNOR",prereqs=listOf("XOR")),

        // ROUTING BIT – address decode for RAM
        LevelDef("MUX","MUX",ChapterId.ROUTING,"MUX bit sel?b:a steering – CPU control decoder","(a&!sel)|(b&sel)",listOf("a","b","sel"),outputs=listOf("out"),targetChipId="MUX",allowedChipIds=listOf("NAND","NOT","AND","OR","XOR"),flavor="Select like CPU",unlocksChipId="MUX",prereqs=listOf("NOR","XNOR")),
        LevelDef("DMUX","DMUX",ChapterId.ROUTING,"DMUX bit in,sel->a,b route – enables RAM","a=in&!sel b=in&sel",listOf("in","sel"),outputs=listOf("a","b"),targetChipId="DMUX",allowedChipIds=listOf("NAND","NOT","AND","OR","XOR","MUX"),unlocksChipId="DMUX",prereqs=listOf("MUX")),
        LevelDef("MUX4","MUX4",ChapterId.ROUTING,"MUX4 2-bit select 00:a 01:b 10:c 11:d","3 MUXes",listOf("a","b","c","d","sel0","sel1"),outputs=listOf("out"),targetChipId="MUX4",allowedChipIds=listOf("NAND","NOT","AND","OR","XOR","MUX","DMUX"),unlocksChipId="MUX4",prereqs=listOf("MUX")),
        LevelDef("DMUX4","DMUX4",ChapterId.ROUTING,"DMUX4 – 1 address -> 4 cells – RAM write decode core","DMUX tree",listOf("in","sel0","sel1"),outputs=listOf("a","b","c","d"),targetChipId="DMUX4",allowedChipIds=listOf("NAND","NOT","AND","OR","XOR","MUX","DMUX","MUX4"),flavor="Addr->cell for RAM",unlocksChipId="DMUX4",prereqs=listOf("DMUX","MUX4")),
        LevelDef("MUX8","MUX8",ChapterId.ROUTING,"MUX8 tree – RAM read bit from 8 sources","2xMUX4+MUX",listOf("a","b","c","d","e","f","g","h","sel0","sel1","sel2"),outputs=listOf("out"),targetChipId="MUX8",allowedChipIds=listOf("NAND","NOT","AND","OR","XOR","MUX","DMUX","MUX4","DMUX4"),unlocksChipId="MUX8",prereqs=listOf("DMUX4")),
        LevelDef("DMUX8","DMUX8",ChapterId.ROUTING,"DMUX8 write-enable tree – selects one reg to write for bit RAM","DMUX4+ branches",listOf("in","sel0","sel1","sel2"),outputs=listOf("a","b","c","d","e","f","g","h"),targetChipId="DMUX8",allowedChipIds=listOf("NAND","NOT","AND","OR","XOR","MUX","DMUX","MUX4","DMUX4","MUX8"),flavor="Write decode",unlocksChipId="DMUX8",prereqs=listOf("MUX8")),

        // BUS expanders (JOIN bits->bus thick) / contractors (SPLIT bus->bits) – makes heavy levels less tedious
        LevelDef(id="JOIN_8",displayName="JOIN8 Exp 8b",chapter=ChapterId.ROUTING,description="JOIN8 Expander: 8 bits -> BUS8[8] thick blue byte bus – one wire instead of 8 thin","OUT[0]=b0 .. OUT[7]=b7 concat",inputs=(0..7).map{"b$it"},inputWidths=List(8){1},outputs=listOf("OUT"),outputWidths=listOf(8),targetChipId="JOIN_8",allowedChipIds=listOf("NAND","NOT","AND","OR","XOR","MUX","DMUX"),flavor="Bits->Bus expander cuts 8 wires to 1",unlocksChipId="JOIN_8",prereqs=listOf("DMUX8")),
        LevelDef(id="SPLIT_8",displayName="SPLIT8 Cont 8b",chapter=ChapterId.ROUTING,description="SPLIT8 Contractor: BUS8[8] thick blue -> 8 bits – split for bit logic","b0=IN[0]..b7=IN[7]",inputs=listOf("IN"),inputWidths=listOf(8),outputs=(0..7).map{"b$it"},outputWidths=List(8){1},targetChipId="SPLIT_8",allowedChipIds=listOf("NAND","JOIN_8"),flavor="Bus->bits contractor",unlocksChipId="SPLIT_8",prereqs=listOf("JOIN_8")),
        LevelDef(id="JOIN_4",displayName="JOIN4 Exp 4b",chapter=ChapterId.ROUTING,description="JOIN4 Expander: 4 bits->BUS4[4] orange opcode nibble bus","concat nibble",inputs=(0..3).map{"b$it"},inputWidths=List(4){1},outputs=listOf("OUT"),outputWidths=listOf(4),targetChipId="JOIN_4",allowedChipIds=listOf("NAND","NOT","AND","OR","JOIN_8","SPLIT_8"),flavor="4-bit bus for opcode decode",unlocksChipId="JOIN_4",prereqs=listOf("JOIN_8")),
        LevelDef(id="SPLIT_4",displayName="SPLIT4 Cont 4b",chapter=ChapterId.ROUTING,description="SPLIT4 Contractor: BUS4[4] orange -> 4 bits","split nibble",inputs=listOf("IN"),inputWidths=listOf(4),outputs=(0..3).map{"b$it"},outputWidths=List(4){1},targetChipId="SPLIT_4",allowedChipIds=listOf("NAND","JOIN_4","JOIN_8","SPLIT_8"),unlocksChipId="SPLIT_4",prereqs=listOf("JOIN_4")),
        LevelDef(id="MUX_B8",displayName="MUX B8 [8]",chapter=ChapterId.ROUTING,description="MUX_B8 bus: A[8],B[8],sel->OUT[8] byte-wide – CPU addr mux PC vs data","Byte MUX via bit MUXes",inputs=listOf("A","B","sel"),inputWidths=listOf(8,8,1),outputs=listOf("OUT"),outputWidths=listOf(8),targetChipId="MUX_B8",allowedChipIds=ChipLibrary.all.keys.filter{it!="CPU" && it!="COMPUTER"}.toList(),flavor="Byte bus MUX – address mux for program-from-RAM",unlocksChipId="MUX_B8",prereqs=listOf("SPLIT_4")),
        LevelDef(id="MUX4_B8",displayName="MUX4 B8 [8]",chapter=ChapterId.ROUTING,description="MUX4_B8 bus: 4xBUS8 -> BUS8 – RAM64 read stage","2xMUX_B8+MUX_B8",inputs=listOf("A","B","C","D","sel0","sel1"),inputWidths=listOf(8,8,8,8,1,1),outputs=listOf("OUT"),outputWidths=listOf(8),targetChipId="MUX4_B8",allowedChipIds=ChipLibrary.all.keys.filter{it!="CPU" && it!="COMPUTER"}.toList(),unlocksChipId="MUX4_B8",prereqs=listOf("MUX_B8")),

        // ARITH – bus versions reduce tedium
        LevelDef("HALF_ADDER","HalfAdd",ChapterId.ARITH,"Half adder sum=a^b carry=a&b","XOR+AND",listOf("a","b"),outputs=listOf("sum","carry"),targetChipId="HALF_ADDER",allowedChipIds=listOf("NAND","NOT","AND","OR","XOR","MUX","JOIN_8","SPLIT_8"),flavor="1-bit addition",unlocksChipId="HALF_ADDER",prereqs=listOf("MUX4_B8")),
        LevelDef("FULL_ADDER","FullAdd",ChapterId.ARITH,"Full adder with carry","2 half+OR",listOf("a","b","cin"),outputs=listOf("sum","cout"),targetChipId="FULL_ADDER",allowedChipIds=listOf("NAND","NOT","AND","OR","XOR","MUX","HALF_ADDER"),unlocksChipId="FULL_ADDER",prereqs=listOf("HALF_ADDER")),
        LevelDef(id="ADDER_4B",displayName="Adder4 B [4]",chapter=ChapterId.ARITH,description="Adder4 bus A[4]+B[4]->S[4],cout – orange [4] wire","Chain FullAdders, use JOIN_4/SPLIT_4",inputs=listOf("A","B"),inputWidths=listOf(4,4),outputs=listOf("S","cout"),outputWidths=listOf(4,1),targetChipId="ADDER_4B",allowedChipIds=listOf("NAND","NOT","AND","OR","XOR","HALF_ADDER","FULL_ADDER","JOIN_4","SPLIT_4"),unlocksChipId="ADDER_4B",prereqs=listOf("FULL_ADDER")),
        LevelDef(id="ADDER_8B",displayName="Adder8 B [8]",chapter=ChapterId.ARITH,description="Adder8 bus A[8]+B[8]->S[8],cout – data path of CPU – thick blue [8]","2xAdder4B bus or 8xFA, JOIN_8 expander",inputs=listOf("A","B"),inputWidths=listOf(8,8),outputs=listOf("S","cout"),outputWidths=listOf(8,1),targetChipId="ADDER_8B",allowedChipIds=listOf("NAND","NOT","AND","OR","XOR","HALF_ADDER","FULL_ADDER","ADDER_4B","JOIN_8","SPLIT_8"),flavor="Bus data path",unlocksChipId="ADDER_8B",prereqs=listOf("FULL_ADDER","ADDER_4B")),
        LevelDef(id="INC_8",displayName="INC8 Bus [8]",chapter=ChapterId.ARITH,description="INC8 Bus: A[8]->S[8] +1 – needed for PC=PC+1","A+1",inputs=listOf("A"),inputWidths=listOf(8),outputs=listOf("S","cout"),outputWidths=listOf(8,1),targetChipId="INC_8",allowedChipIds=listOf("NAND","NOT","AND","OR","XOR","HALF_ADDER","FULL_ADDER","ADDER_8B"),flavor="PC increments via INC_8",unlocksChipId="INC_8",prereqs=listOf("ADDER_8B")),
        LevelDef(id="SUB_8",displayName="SUB8 B [8]",chapter=ChapterId.ARITH,description="SUB8 bus A[8]-B[8]->S[8],borrow – ALU sub op","A+~B+1",inputs=listOf("A","B"),inputWidths=listOf(8,8),outputs=listOf("S","borrow"),outputWidths=listOf(8,1),targetChipId="SUB_8",allowedChipIds=listOf("NAND","NOT","AND","OR","XOR","ADDER_8B","INC_8","JOIN_8","SPLIT_8"),unlocksChipId="SUB_8",prereqs=listOf("INC_8")),

        // MEMORY – toward program-from-RAM computer
        LevelDef("SR_LATCH","SR Latch",ChapterId.MEMORY,"SR latch NOR cross-coupled – first memory hold via feedback – S=1 sets, R=1 resets","2 NOR cross",listOf("S","R"),outputs=listOf("Q","nQ"),targetChipId="SR_LATCH",allowedChipIds=listOf("NAND","NOR","NOT","AND","OR"),flavor="Time enters – bit remembers via feedback",unlocksChipId="SR_LATCH",prereqs=listOf("SUB_8")),
        LevelDef("DFF","DFF",ChapterId.MEMORY,"D Flip-Flop edge-triggered – on clk rise Q=D","Master-slave",listOf("d","clk"),outputs=listOf("q"),targetChipId="DFF",allowedChipIds=listOf("NAND","NOT","AND","OR","XOR","MUX","SR_LATCH"),flavor="Clocked remember D",unlocksChipId="DFF",prereqs=listOf("SR_LATCH")),
        LevelDef("BIT","Bit",ChapterId.MEMORY,"1-bit register with load – if load=1 store in","MUX+DFF",listOf("in","load"),outputs=listOf("out"),targetChipId="BIT",allowedChipIds=ChipLibrary.all.keys.filter{it!="CPU" && it!="COMPUTER" && it!="RAM_256B"}.toList(),unlocksChipId="BIT",prereqs=listOf("DFF")),
        LevelDef(id="REG_8B",displayName="Reg8 B [8]",chapter=ChapterId.MEMORY,description="Reg8 bus IN[8],load->OUT[8] – accumulator A – thick blue [8]","8xBIT common load – JOIN_8 makes one bus",inputs=listOf("IN","load"),inputWidths=listOf(8,1),outputs=listOf("OUT"),outputWidths=listOf(8),targetChipId="REG_8B",allowedChipIds=ChipLibrary.all.keys.filter{it!="CPU" && it!="COMPUTER"}.toList(),unlocksChipId="REG_8B",prereqs=listOf("BIT")),
        LevelDef(id="RAM_8B",displayName="RAM8 B [8]",chapter=ChapterId.MEMORY,description="RAM8 bus 8x8: IN[8],load,ADDR[3]->OUT[8] – DMUX8_B+ MUX8_B8 – small program mem","DMUX8_B + MUX8_B8 bus",inputs=listOf("IN","load","ADDR"),inputWidths=listOf(8,1,3),outputs=listOf("OUT"),outputWidths=listOf(8),targetChipId="RAM_8B",allowedChipIds=ChipLibrary.all.keys.filter{it!="CPU" && it!="COMPUTER"}.toList(),unlocksChipId="RAM_8B",prereqs=listOf("REG_8B")),
        LevelDef(id="RAM_64B",displayName="RAM64 B [8]",chapter=ChapterId.MEMORY,description="RAM64 bus 64x8 data – 6-bit address","high bits via MUX",inputs=listOf("IN","load","ADDR"),inputWidths=listOf(8,1,6),outputs=listOf("OUT"),outputWidths=listOf(8),targetChipId="RAM_64B",allowedChipIds=ChipLibrary.all.keys.filter{it!="CPU" && it!="COMPUTER"}.toList(),unlocksChipId="RAM_64",prereqs=listOf("RAM_8B")),
        LevelDef(id="PC_8",displayName="PC Bus [8]",chapter=ChapterId.MEMORY,description="Program Counter bus: IN[8],inc,load,reset->OUT[8] – instruction pointer","REG_8B+INC_8+MUX_B8",inputs=listOf("IN","inc","load","reset"),inputWidths=listOf(8,1,1,1),outputs=listOf("OUT"),outputWidths=listOf(8),targetChipId="PC_8",allowedChipIds=ChipLibrary.all.keys.filter{it!="CPU" && it!="COMPUTER"}.toList(),flavor="Where next instruction? PC needed",unlocksChipId="PC_8",prereqs=listOf("REG_8B")),
        LevelDef(id="RAM_256B",displayName="RAM256 Main [8]",chapter=ChapterId.MEMORY,description="RAM256 unified main memory 256x8 von Neumann – program+data share SAME RAM","4xRAM64B hierarchy, thick bus",inputs=listOf("IN","load","ADDR"),inputWidths=listOf(8,1,8),outputs=listOf("OUT"),outputWidths=listOf(8),targetChipId="RAM_256B",allowedChipIds=ChipLibrary.all.keys.filter{it!="CPU" && it!="COMPUTER"}.toList(),flavor="Main memory – programs live here too",unlocksChipId="RAM_256B",prereqs=listOf("RAM_64B","PC_8")),

        // CPU – fetch/decode/execute FROM RAM
        LevelDef(id="ALU_8",displayName="ALU Bus [8]",chapter=ChapterId.CPU,description="ALU bus 8-bit: A[8],B[8],OP[3]->OUT[8],zero,carry,neg – OP 000ADD 001SUB 010AND 011OR 100XOR 101NOT 110INC 111DEC","Byte ALU – bus cuts tedium from 16 thin to 2 thick",inputs=listOf("A","B","OP"),inputWidths=listOf(8,8,3),outputs=listOf("OUT","zero","carry","neg"),outputWidths=listOf(8,1,1,1),targetChipId="ALU_8",allowedChipIds=ChipLibrary.all.keys.filter{it!="CPU" && it!="COMPUTER"}.toList(),flavor="Calculator of CPU – ALU with flags",unlocksChipId="ALU_8",prereqs=listOf("RAM_256B","SUB_8")),
        LevelDef(id="CPU",displayName="CPU Bus [4][8]",chapter=ChapterId.CPU,description="CPU bus accumulator fetch-decode-execute FROM RAM: Inputs OPCODE[4] orange bus via SPLIT_4 decode, ADDR[8] blue bus, DATA[8] blue from RAM main memory reset->OUT[8],writeM,ADDR_M[8],PC[8]. ISA: LDA addr A=RAM[addr], STA RAM[addr]=A, ADD, SUB, JMP, JZ (needs zero flag), LDI, HLT. Needs PC_8 inc via INC_8, ALU_8, REG_8B A register, DMUX8 opcode->control, MUX_B8 bus address mux PC vs ADDR_M because RAM shared program+data.","PC_8+ALU_8+REG_8B+MUX_B8",inputs=listOf("OPCODE","ADDR","DATA","reset"),inputWidths=listOf(4,8,8,1),outputs=listOf("OUT","writeM","ADDR_M","PC"),outputWidths=listOf(8,1,8,8),targetChipId="CPU",allowedChipIds=ChipLibrary.all.keys.toList(),flavor="Fetch-decode-execute – program code FROM RAM",unlocksChipId="CPU",prereqs=listOf("ALU_8")),
        LevelDef(id="CPU_8",displayName="CPU compat",chapter=ChapterId.CPU,description="CPU compat alias for old saves","Same as CPU bus",inputs=listOf("OPCODE","ADDR","DATA","reset"),inputWidths=listOf(4,8,8,1),outputs=listOf("OUT","writeM","ADDR_M","PC"),outputWidths=listOf(8,1,8,8),targetChipId="CPU_8",allowedChipIds=ChipLibrary.all.keys.toList(),unlocksChipId="CPU_8",prereqs=listOf("CPU")),
        LevelDef(id="COMPUTER",displayName="Computer RAM [8]",chapter=ChapterId.CPU,description="Final stored-program Computer: CPU+RAM_256B Main Memory unified + MUX_B8 address mux + JOIN/SPLIT bus [4][8]. von Neumann: RAM holds both instruction bytes (OPCODE[4] orange + ADDR[8] blue) and data bytes same space. Ref program mem[16]=5,17=3 prog @0 LDA16 ADD17 STA18 HLT -> RAM[18]=8 -> OUT[8]=8. Must wire CPU.PC[8] blue->MUX_B8 A[8] for fetch, CPU.ADDR_M[8]->MUX_B8 B[8] for data, MUX.OUT[8]->RAM ADDR[8] bus, RAM.OUT[8]->CPU.DATA[8] bus and via SPLIT_4 contractor->OPCODE[4] orange for decode, CPU.OUT[8]->RAM.IN[8] bus. Need bus converters.","Von Neumann: PC[8]->MUX_B8 A, ADDR_M->B, MUX->RAM ADDR, RAM OUT[8]->CPU DATA + SPLIT_4 opcode",inputs=listOf("reset"),inputWidths=listOf(1),outputs=listOf("OUT"),outputWidths=listOf(8),targetChipId="COMPUTER",allowedChipIds=ChipLibrary.all.keys.toList(),flavor="NAND->Computer running program FROM RAM (von Neumann) 1/4/8-bit buses",unlocksChipId="COMPUTER",prereqs=listOf("CPU_8"))
    )

    val chapters: List<Chapter> = listOf(
        Chapter(ChapterId.FOUNDATION,"Foundation","NAND->XNOR basics",all.filter{it.chapter==ChapterId.FOUNDATION}.map{it.id}),
        Chapter(ChapterId.ROUTING,"Routing + Bus","Muxes + JOIN/SPLIT expander/contractor bus [4][8] – less tedious – address decode",all.filter{it.chapter==ChapterId.ROUTING}.map{it.id}),
        Chapter(ChapterId.ARITH,"Arithmetic Bus","Bus adders orange [4] blue [8] + INC_8 PC+1 + SUB_8 ALU",all.filter{it.chapter==ChapterId.ARITH}.map{it.id}),
        Chapter(ChapterId.MEMORY,"Memory -> Main","Latch->DFF->REG_B bus->RAM hierarchy bus->PC->RAM256B unified prog+data",all.filter{it.chapter==ChapterId.MEMORY}.map{it.id}),
        Chapter(ChapterId.CPU,"CPU -> Computer RAM","ALU bus -> CPU bus fetch from RAM -> COMPUTER stored-program",all.filter{it.chapter==ChapterId.CPU}.map{it.id}),
    )

    val byId: Map<String, LevelDef> = all.associateBy { it.id }
    fun get(id: String): LevelDef = byId[id] ?: error("Unknown level $id – use JOIN_8 expander or SPLIT_8 contractor")

    val timelineRows: List<List<String>> = listOf(
        listOf("NOT"),listOf("AND"),listOf("OR"),listOf("XOR"),listOf("NOR","XNOR"),
        listOf("MUX"),listOf("DMUX","MUX4"),listOf("DMUX4"),listOf("MUX8"),listOf("DMUX8"),
        listOf("JOIN_8","JOIN_4"),listOf("SPLIT_8","SPLIT_4"),listOf("MUX_B8","MUX4_B8"),
        listOf("HALF_ADDER"),listOf("FULL_ADDER","ADDER_4B"),listOf("ADDER_8B"),listOf("INC_8"),listOf("SUB_8"),
        listOf("SR_LATCH"),listOf("DFF"),listOf("BIT"),listOf("REG_8B"),listOf("RAM_8B","PC_8"),listOf("RAM_64B"),listOf("RAM_256B"),
        listOf("ALU_8"),listOf("CPU"),listOf("CPU_8"),listOf("COMPUTER")
    )

    fun availableLevels(completed: Set<String>): Set<String> = all.filter{ it.id !in completed && it.prereqs.all{ p-> p in completed } }.map{ it.id }.toSet()
}

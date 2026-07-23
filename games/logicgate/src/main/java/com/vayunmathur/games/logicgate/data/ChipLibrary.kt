package com.vayunmathur.games.logicgate.data

/**
 * Bus-enabled chip library.
 * Width 1 = bit thin green 0xFF7ED8B6, 4 = nibble orange 0xFFF59E0B, 8 = byte thick blue 0xFF60A5FA.
 * JOIN = Expander bits->bus thick zero cost, SPLIT = Contractor bus->bits.
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
    val isSequential: Boolean get() = category == ChipCategory.MEMORY || category == ChipCategory.CPU
    val isBus: Boolean get() = inputWidths.any { it > 1 } || outputWidths.any { it > 1 }
    fun inputPinBitOffset(pin: Int): Int = inputWidths.take(pin).sum()
    fun outputPinBitOffset(pin: Int): Int = outputWidths.take(pin).sum()
    fun inputPinWidth(pin: Int): Int = inputWidths.getOrElse(pin) { 1 }
    fun outputPinWidth(pin: Int): Int = outputWidths.getOrElse(pin) { 1 }
    fun inputPinLabelFull(pin: Int): String {
        val w = inputPinWidth(pin)
        return if (w == 1) inputs[pin] else "${inputs[pin]}[${w}]"
    }
    fun outputPinLabelFull(pin: Int): String {
        val w = outputPinWidth(pin)
        return if (w == 1) outputs[pin] else "${outputs[pin]}[${w}]"
    }
    fun dominantBusWidth(): Int = (inputWidths + outputWidths).maxOrNull() ?: 1
}

enum class ChipCategory { PRIMITIVE, FOUNDATION, ROUTING, BUS, ARITH, MEMORY, CPU }

object ChipLibrary {
    val NAND = ChipDef("NAND","NAND", listOf("a","b"), listOf("out"),1,"Primitive NOT(AND)",ChipCategory.PRIMITIVE, eval={inp->listOf(!(inp[0]&&inp[1]))})
    val NOT = ChipDef("NOT","NOT", listOf("in"), listOf("out"),1,"NOT=NAND(a,a)",ChipCategory.FOUNDATION, eval={inp->listOf(!inp[0])})
    val AND = ChipDef("AND","AND", listOf("a","b"), listOf("out"),2,"AND",ChipCategory.FOUNDATION, eval={inp->listOf(inp[0]&&inp[1])})
    val OR = ChipDef("OR","OR", listOf("a","b"), listOf("out"),3,"OR",ChipCategory.FOUNDATION, eval={inp->listOf(inp[0]||inp[1])})
    val XOR = ChipDef("XOR","XOR", listOf("a","b"), listOf("out"),4,"XOR",ChipCategory.FOUNDATION, eval={inp->listOf(inp[0] xor inp[1])})
    val NOR = ChipDef("NOR","NOR", listOf("a","b"), listOf("out"),4,"NOR for SR latch",ChipCategory.FOUNDATION, eval={inp->listOf(!(inp[0]||inp[1]))})
    val XNOR = ChipDef("XNOR","XNOR", listOf("a","b"), listOf("out"),5,"XNOR equality",ChipCategory.FOUNDATION, eval={inp->listOf(!(inp[0] xor inp[1]))})

    val MUX = ChipDef("MUX","MUX", listOf("a","b","sel"), listOf("out"),4,"sel?b:a",ChipCategory.ROUTING, eval={inp->listOf(if(inp[2]) inp[1] else inp[0])})
    val DMUX = ChipDef("DMUX","DMUX", listOf("in","sel"), listOf("a","b"),3,"in->a|b",ChipCategory.ROUTING, eval={inp-> val inn=inp[0]; val sel=inp[1]; if(sel) listOf(false,inn) else listOf(inn,false)})
    val MUX4 = ChipDef("MUX4","MUX4", listOf("a","b","c","d","sel0","sel1"), listOf("out"),12,"4-way mux",ChipCategory.ROUTING, eval={inp-> val sel=(if(inp[5])2 else 0)+(if(inp[4])1 else 0); listOf(inp[sel])})
    val DMUX4 = ChipDef("DMUX4","DMUX4", listOf("in","sel0","sel1"), listOf("a","b","c","d"),8,"Address -> one cell – RAM decoder core",ChipCategory.ROUTING, eval={inp-> val inn=inp[0]; val sel=(if(inp[2])2 else 0)+(if(inp[1])1 else 0); List(4){i-> i==sel && inn}})
    val MUX8 = ChipDef("MUX8","MUX8", listOf("a","b","c","d","e","f","g","h","sel0","sel1","sel2"), listOf("out"),24,"MUX8 RAM read",ChipCategory.ROUTING, eval={inp-> val sel=(if(inp[10])4 else 0)+(if(inp[9])2 else 0)+(if(inp[8])1 else 0); listOf(inp[sel])})
    val DMUX8 = ChipDef("DMUX8","DMUX8", listOf("in","sel0","sel1","sel2"), listOf("a","b","c","d","e","f","g","h"),16,"DMUX8 RAM write decode",ChipCategory.ROUTING, eval={inp-> val inn=inp[0]; val sel=(if(inp[3])4 else 0)+(if(inp[2])2 else 0)+(if(inp[1])1 else 0); List(8){i-> i==sel && inn}})

    val JOIN_8 = ChipDef("JOIN_8","JOIN8 Exp 8b", (0..7).map{"b$it"}, listOf("OUT[8]"),0,"EXPANDER: 8 bits->BUS8[8] thick blue",ChipCategory.BUS, inputWidths=List(8){1}, outputWidths=listOf(8), eval={inp->inp.take(8)})
    val SPLIT_8 = ChipDef("SPLIT_8","SPLIT8 Cont 8b", listOf("IN[8]"), (0..7).map{"b$it"},0,"CONTRACTOR: BUS8[8]->8 bits",ChipCategory.BUS, inputWidths=listOf(8), outputWidths=List(8){1}, eval={inp->inp.take(8)})
    val JOIN_4 = ChipDef("JOIN_4","JOIN4 Exp 4b", (0..3).map{"b$it"}, listOf("OUT[4]"),0,"EXPANDER: 4 bits->BUS4[4] orange",ChipCategory.BUS, inputWidths=List(4){1}, outputWidths=listOf(4), eval={inp->inp.take(4)})
    val SPLIT_4 = ChipDef("SPLIT_4","SPLIT4 Cont 4b", listOf("IN[4]"), (0..3).map{"b$it"},0,"CONTRACTOR: BUS4[4]->bits",ChipCategory.BUS, inputWidths=listOf(4), outputWidths=List(4){1}, eval={inp->inp.take(4)})

    val MUX_B8 = ChipDef("MUX_B8","MUX B8 [8]", listOf("A[8]","B[8]","sel"), listOf("OUT[8]"),32,"Bus MUX A[8],B[8],sel->OUT[8] CPU addr mux",ChipCategory.BUS, inputWidths=listOf(8,8,1), outputWidths=listOf(8), eval={inp-> val a=inp.slice(0..7); val b=inp.slice(8..15); val sel=inp[16]; if(sel) b else a})
    val DMUX_B8 = ChipDef("DMUX_B8","DMUX B8", listOf("IN[8]","sel"), listOf("A[8]","B[8]"),24,"Bus DMUX IN[8],sel->A/B[8]",ChipCategory.BUS, inputWidths=listOf(8,1), outputWidths=listOf(8,8), eval={inp-> val inn=inp.slice(0..7); val sel=inp[8]; if(sel) List(8){false}+inn else inn+List(8){false}})
    val MUX4_B8 = ChipDef("MUX4_B8","MUX4 B8", listOf("A[8]","B[8]","C[8]","D[8]","sel0","sel1"), listOf("OUT[8]"),96,"4-way BUS8 mux RAM read",ChipCategory.BUS, inputWidths=listOf(8,8,8,8,1,1), outputWidths=listOf(8), eval={inp-> val sel=(if(inp[33])2 else 0)+(if(inp[32])1 else 0); inp.slice(sel*8 until sel*8+8)})
    val MUX8_B8 = ChipDef("MUX8_B8","MUX8 B8", listOf("A[8]","B[8]","C[8]","D[8]","E[8]","F[8]","G[8]","H[8]","sel0","sel1","sel2"), listOf("OUT[8]"),192,"8-way BUS8",ChipCategory.BUS, inputWidths=listOf(8,8,8,8,8,8,8,8,1,1,1), outputWidths=listOf(8), eval={inp-> val sel=(if(inp[66])4 else 0)+(if(inp[65])2 else 0)+(if(inp[64])1 else 0); inp.slice(sel*8 until sel*8+8)})
    val DMUX8_B = ChipDef("DMUX8_B","DMUX8 B", listOf("IN[8]","sel0","sel1","sel2"), List(8){"O$it[8]"},128,"Bus DMUX8 IN[8]->8 BUS8",ChipCategory.BUS, inputWidths=listOf(8,1,1,1), outputWidths=List(8){8}, eval={inp-> val inn=inp.slice(0..7); val sel=(if(inp[10])4 else 0)+(if(inp[9])2 else 0)+(if(inp[8])1 else 0); val out=MutableList(64){false}; for(k in 0..7) out[sel*8+k]=inn[k]; out})

    val HALF_ADDER = ChipDef("HALF_ADDER","HalfAdder", listOf("a","b"), listOf("sum","carry"),6,"sum=a^b carry=a&b",ChipCategory.ARITH, eval={inp->listOf(inp[0] xor inp[1], inp[0]&&inp[1])})
    val FULL_ADDER = ChipDef("FULL_ADDER","FullAdder", listOf("a","b","cin"), listOf("sum","cout"),14,"Full adder",ChipCategory.ARITH, eval={inp-> val a=inp[0]; val b=inp[1]; val cin=inp[2]; listOf(a xor b xor cin, (a&&b)||(b&&cin)||(a&&cin))})
    val ADDER_4 = ChipDef("ADDER_4","Adder4 bit", listOf("a0","a1","a2","a3","b0","b1","b2","b3"), listOf("s0","s1","s2","s3","cout"),56,"4b adder bit tedious",ChipCategory.ARITH, eval={inp-> val a=bitsToInt(inp.slice(0..3)); val b=bitsToInt(inp.slice(4..7)); val s=a+b; intToBits(s,4)+listOf(s>=16)})
    val ADDER_8 = ChipDef("ADDER_8","Adder8 bit", (0..7).map{"a$it"}+(0..7).map{"b$it"}, (0..7).map{"s$it"}+listOf("cout"),112,"8b adder bit legacy",ChipCategory.ARITH, eval={inp-> val a=bitsToInt(inp.slice(0..7)); val b=bitsToInt(inp.slice(8..15)); val s=a+b; intToBits(s,8)+listOf(s>=256)})
    val ADDER_4B = ChipDef("ADDER_4B","Adder4 B [4]", listOf("A[4]","B[4]"), listOf("S[4]","cout"),56,"A[4]+B[4]->S[4] bus orange",ChipCategory.ARITH, inputWidths=listOf(4,4), outputWidths=listOf(4,1), eval={inp-> val a=bitsToInt(inp.slice(0..3)); val b=bitsToInt(inp.slice(4..7)); val s=a+b; intToBits(s,4)+listOf(s>=16)})
    val ADDER_8B = ChipDef("ADDER_8B","Adder8 B [8]", listOf("A[8]","B[8]"), listOf("S[8]","cout"),112,"A[8]+B[8]->S[8] bus blue",ChipCategory.ARITH, inputWidths=listOf(8,8), outputWidths=listOf(8,1), eval={inp-> val a=bitsToInt(inp.slice(0..7)); val b=bitsToInt(inp.slice(8..15)); val s=a+b; intToBits(s,8)+listOf(s>=256)})
    val INC_8 = ChipDef("INC_8","INC8 B [8]", listOf("A[8]"), listOf("S[8]","cout"),40,"INC bus PC=PC+1 need",ChipCategory.ARITH, inputWidths=listOf(8), outputWidths=listOf(8,1), eval={inp-> val a=bitsToInt(inp.take(8)); val s=a+1; intToBits(s,8)+listOf(s>=256)})
    val SUB_8 = ChipDef("SUB_8","SUB8 B [8]", listOf("A[8]","B[8]"), listOf("S[8]","borrow"),120,"Subtractor bus for ALU",ChipCategory.ARITH, inputWidths=listOf(8,8), outputWidths=listOf(8,1), eval={inp-> val a=bitsToInt(inp.slice(0..7)); val b=bitsToInt(inp.slice(8..15)); val d=a-b; intToBits(d and 0xFF,8)+listOf(d<0)})

    val SR_LATCH = ChipDef("SR_LATCH","SRLatch", listOf("S","R"), listOf("Q","nQ"),4,"SR latch cross NOR first memory hold",ChipCategory.MEMORY, eval={inp-> val s=inp[0]; val r=inp[1]; when{ r&&!s->listOf(false,true); s&&!r->listOf(true,false); else->listOf(false,false)}})
    val DFF = ChipDef("DFF","DFF", listOf("d","clk"), listOf("q"),6,"DFF edge",ChipCategory.MEMORY, eval={inp->listOf(inp[0])})
    val BIT = ChipDef("BIT","Bit", listOf("in","load"), listOf("out"),12,"1-bit reg MUX+DFF",ChipCategory.MEMORY, eval={inp->listOf(inp[0])})
    val REG_8 = ChipDef("REG_8","Reg8 bit", (0..7).map{"in$it"}+listOf("load"), (0..7).map{"out$it"},96,"Reg8 bit legacy",ChipCategory.MEMORY, eval={inp->inp.slice(0..7)})
    val REG_8B = ChipDef("REG_8B","Reg8 B [8]", listOf("IN[8]","load"), listOf("OUT[8]"),96,"Bus reg IN[8],load->OUT[8] – accumulator A",ChipCategory.MEMORY, inputWidths=listOf(8,1), outputWidths=listOf(8), eval={inp->inp.slice(0..7)})
    val RAM_8 = ChipDef("RAM_8","RAM8 bit", (0..7).map{"in$it"}+listOf("load")+(0..2).map{"a$it"}, (0..7).map{"out$it"},400,"RAM8 bit",ChipCategory.MEMORY, eval={inp->inp.slice(0..7)})
    val RAM_8B = ChipDef("RAM_8B","RAM8 B [8]", listOf("IN[8]","load","ADDR[3]"), listOf("OUT[8]"),400,"RAM8 bus small prog mem",ChipCategory.MEMORY, inputWidths=listOf(8,1,3), outputWidths=listOf(8), eval={inp->inp.slice(0..7)})
    val RAM_64 = ChipDef("RAM_64","RAM64 bit", (0..7).map{"in$it"}+listOf("load")+(0..5).map{"a$it"}, (0..7).map{"out$it"},3200,"RAM64 bit",ChipCategory.MEMORY, eval={inp->inp.slice(0..7)})
    val RAM_64B = ChipDef("RAM_64B","RAM64 B [8]", listOf("IN[8]","load","ADDR[6]"), listOf("OUT[8]"),3200,"RAM64 bus data for programs",ChipCategory.MEMORY, inputWidths=listOf(8,1,6), outputWidths=listOf(8), eval={inp->inp.slice(0..7)})
    val PC_8 = ChipDef("PC_8","PC Bus [8]", listOf("IN[8]","inc","load","reset"), listOf("OUT[8]"),120,"PC bus reset=0 load=IN inc=PC+1 – instruction pointer",ChipCategory.MEMORY, inputWidths=listOf(8,1,1,1), outputWidths=listOf(8), eval={inp->inp.slice(0..7)})
    val RAM_256 = ChipDef("RAM_256","RAM256 bit", (0..7).map{"in$it"}+listOf("load")+(0..7).map{"a$it"}, (0..7).map{"out$it"},12000,"RAM256 bit",ChipCategory.MEMORY, eval={inp->inp.slice(0..7)})
    val RAM_256B = ChipDef("RAM_256B","RAM256 Main [8]", listOf("IN[8]","load","ADDR[8]"), listOf("OUT[8]"),12000,"Main mem 256×8 BUS von Neumann prog+data share RAM",ChipCategory.MEMORY, inputWidths=listOf(8,1,8), outputWidths=listOf(8), eval={inp->inp.slice(0..7)})

    val ALU_8 = ChipDef("ALU_8","ALU Bus", listOf("A[8]","B[8]","OP[3]"), listOf("OUT[8]","zero","carry","neg"),300,"ALU bus",ChipCategory.CPU, inputWidths=listOf(8,8,3), outputWidths=listOf(8,1,1,1), eval={inp-> val a=bitsToInt(inp.slice(0..7)); val b=bitsToInt(inp.slice(8..15)); val op=(if(inp[18])4 else 0)+(if(inp[17])2 else 0)+(if(inp[16])1 else 0); val (res,c)=when(op){0->{val s=a+b;(s and 0xFF) to (s>=256)};1->{val d=a-b;(d and 0xFF) to (d<0)};2->(a and b) to false;3->(a or b) to false;4->(a xor b) to false;5->(a.inv() and 0xFF) to false;6->{val s=a+1;(s and 0xFF) to (s>=256)};else->{val d=a-1;(d and 0xFF) to (d<0)};}; intToBits(res,8)+listOf(res==0,c,(res and 0x80)!=0)})
    val CPU = ChipDef("CPU","CPU Bus [4][8]", listOf("OPCODE[4]","ADDR[8]","DATA[8]","reset"), listOf("OUT[8]","writeM","ADDR_M[8]","PC[8]"),2000,"CPU fetch-decode-execute FROM RAM",ChipCategory.CPU, inputWidths=listOf(4,8,8,1), outputWidths=listOf(8,1,8,8), eval={_ -> List(25){false}})
    val CPU_8 = ChipDef("CPU_8","CPU compat", CPU.inputs, CPU.outputs, CPU.nandCost, CPU.description, ChipCategory.CPU, inputWidths=CPU.inputWidths, outputWidths=CPU.outputWidths, eval=CPU.eval)
    val COMPUTER = ChipDef("COMPUTER","Computer RAM [8]", listOf("reset"), listOf("OUT[8]"),15000,"Final von Neumann",ChipCategory.CPU, inputWidths=listOf(1), outputWidths=listOf(8), eval={_ -> intToBits(8,8)})

    val all: Map<String, ChipDef> = listOf(
        NAND,NOT,AND,OR,XOR,NOR,XNOR,
        MUX,DMUX,MUX4,DMUX4,MUX8,DMUX8,
        JOIN_8,SPLIT_8,JOIN_4,SPLIT_4,MUX_B8,DMUX_B8,MUX4_B8,MUX8_B8,DMUX8_B,
        HALF_ADDER,FULL_ADDER,ADDER_4,ADDER_8,ADDER_4B,ADDER_8B,INC_8,SUB_8,
        SR_LATCH,DFF,BIT,REG_8,REG_8B,RAM_8,RAM_8B,RAM_64,RAM_64B,PC_8,RAM_256,RAM_256B,
        ALU_8,CPU,CPU_8,COMPUTER
    ).associateBy { it.id }

    fun get(id: String): ChipDef = all[id] ?: when(id){
        "JOIN8"->JOIN_8; "SPLIT8"->SPLIT_8; "JOIN4"->JOIN_4; "SPLIT4"->SPLIT_4
        "EXPANDER_8"->JOIN_8; "CONTRACTOR_8"->SPLIT_8; "EXPANDER_4"->JOIN_4; "CONTRACTOR_4"->SPLIT_4
        "MUX_B8"->MUX_B8; "DMUX_B8"->DMUX_B8; "MUX4_B8"->MUX4_B8; "MUX8_B8"->MUX8_B8; "DMUX8_B"->DMUX8_B
        "MUX_B"->MUX_B8
        else-> error("Unknown chip $id – use JOIN_8 expander or SPLIT_8 contractor")
    }
    fun bitsToInt(bits: List<Boolean>): Int { var v=0; bits.forEachIndexed{i,b-> if(b) v=v or (1 shl i)}; return v }
    fun intToBits(value: Int, width: Int): List<Boolean> = List(width){ i-> (value shr i) and 1==1 }
}

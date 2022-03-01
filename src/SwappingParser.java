import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class SwappingParser {
	public static final int _EOF = 0;
	public static final int _ident = 1;
	public static final int _index = 2;
	public static final int _other = 3;
	public static final int _qreg = 4;
	public static final int _creg = 5;
	public static final int _xgate = 6;
	public static final int _rzgate = 7;
	public static final int _sxgate = 8;
	public static final int _cxgate = 9;
	public static final int _swapgate = 10;
	public static final int _barrier = 11;
	public static final int _measure = 12;
	public static final int maxT = 24;

	static final boolean _T = true;
	static final boolean _x = false;
	static final int minErrDist = 2;

	public Token t;    // last recognized token
	public Token la;   // lookahead token
	int errDist = minErrDist;
	
	public Scanner scanner;
	public MappingErrors errors;

	private final LogicalReg logicalReg;
	private final PhysicalReg physicalReg;
	private final File out;
	boolean newFile = true;
	int cost = 0;
	int numSwaps = 0;
	int numCX = 0;

	final int RZ_COST = 0;
	final int X_COST = 1;
	final int CNOT_COST = 10;
	final int SWAP_COST = 30;

	private final boolean DEBUG;

	public SwappingParser(Scanner scanner, File out, LogicalReg logicalReg, PhysicalReg physicalReg, boolean debug) {
		this.scanner = scanner;
		this.out = out;
		this.logicalReg = logicalReg;
		this.physicalReg = physicalReg;
		errors = new MappingErrors();
		DEBUG = debug;
	}

	void commentInitialMapping(){
		appendText("//i");
		logicalReg.getInitialRegs().forEach( p -> {
			String pre = p.getFirst();
			int toParse = p.getSecond();

			for (int i = 0; i < toParse; i++) {
				String qubit = logicalReg.getQubits().get(pre + "[" + i + "]").getPhysicalQubit().toString();
				appendText(" " + qubit);
			}
		});
		appendText("\n\n");
	}

	void appendText(String val){
		FileOutputStream f;
		try {
			if(newFile) {
				f = new FileOutputStream(out, false);
				f.write("".getBytes(StandardCharsets.UTF_8));
				newFile = false;
			}
			f = new FileOutputStream(out, true);
			f.write(val.getBytes(StandardCharsets.UTF_8));
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	void SynErr (int n) {
		if (errDist >= minErrDist) errors.SynErr(la.line, la.col, n);
		errDist = 0;
	}

	public void SemErr (String msg) {
		if (errDist >= minErrDist) errors.SemErr(t.line, t.col, msg);
		errDist = 0;
	}
	
	void Get () {
		for (;;) {
			t = la;
			la = scanner.Scan();
			if (la.kind <= maxT) {
				++errDist;
				break;
			}

			la = t;
		}
	}
	
	void Expect (int n) {
		if (la.kind==n) Get(); else { SynErr(n); }
	}
	
	boolean StartOf (int s) {
		return set[s][la.kind];
	}
	
	void ExpectWeak (int n, int follow) {
		if (la.kind == n) Get();
		else {
			SynErr(n);
			while (!StartOf(follow)) Get();
		}
	}
	
	boolean WeakSeparator (int n, int syFol, int repFol) {
		int kind = la.kind;
		if (kind == n) { Get(); return true; }
		else if (StartOf(repFol)) return false;
		else {
			SynErr(n);
			while (!(set[syFol][kind] || set[repFol][kind] || set[0][kind])) {
				Get();
				kind = la.kind;
			}
			return StartOf(syFol);
		}
	}
	
	void QasmProgram() {
		commentInitialMapping();

		Expect(13);
		appendText(t.val + " ");
		Expect(14);
		appendText(t.val + "\n");
		Expect(15);
		appendText(t.val + " ");
		Expect(16);
		appendText(t.val + "\n");

		// define new Qreg (no more VarDecl needed)
		String qregName = PhysicalReg.QREG_NAME + "[" + physicalReg.getQubits().size() + "]";
		appendText("qreg " + qregName + ";\n");

		while (StartOf(1)) {
			if (la.kind == 4 || la.kind == 5) {
				VarDecl();
			} else {
				Statement();
			}
		}
	}

	void VarDecl() {
		if (la.kind == 4) {
			Get();
			Expect(1);
			if (la.kind == 17) {
				Get();
				Expect(2);
				Expect(18);
			}
		} else if (la.kind == 5) {
			Get();
			appendText(t.val + " ");
			Expect(1);
			String s = t.val;
			if (la.kind == 17) {
				Get();
				Expect(2);
				s = s + "[" + t.val + "]";
				Expect(18);
			}
			appendText(s + ";\n");
		} else SynErr(25);
		Expect(19);
	}

	void Statement() {
		switch (la.kind) {
		case 7: {
			Get();
			cost += RZ_COST; String s = t.val;
			Expect(20);
			String val = Param();
			Expect(21);
			LogicalQubit bit = VarAccess();
			appendText(s + "("+ val + ") " + logicalReg.getPhysical(bit));
			break;
		}
		case 8: {
			Get();
			cost += X_COST; String s = t.val; 
			LogicalQubit bit = VarAccess();
			appendText(s + " " + logicalReg.getPhysical(bit));
			break;
		}
		case 9: {
			Get();
			cost += CNOT_COST; String s = t.val;
			LogicalQubit bit1 = VarAccess();
			Expect(22);
			LogicalQubit bit2 = VarAccess();
			numCX++;

			// TODO: Move to Main.kt
			if(DEBUG) System.out.printf("%s (physical: %s) to %s (physical: %s) || ", bit1, logicalReg.getPhysical(bit1), bit2, logicalReg.getPhysical(bit2));
			List<PhysicalQubit> steps = logicalReg.makeNeighbors(bit1, bit2);
			if(DEBUG) System.out.printf("Swap needed: %s\n", (steps.size() > 1));
			PhysicalQubit prev = null;
			for (PhysicalQubit step : steps) {
				if (prev != null) {
					if(DEBUG) System.out.printf("- Swapping: %s with %s\n", prev, step);
					appendText("swap " + prev + "," + step + ";\n");
					cost += SWAP_COST;
					numSwaps++;
				}
				prev = step;
			}
			// TODO: until here; have method return cost
			//  int numSwaps = logicalReg.performSwapIfNeeded();
			//  cost += SWAP_COST * numSwaps;

			appendText(s + " " + logicalReg.getPhysical(bit1) + "," + logicalReg.getPhysical(bit2));
			break;
		}
		case 6: {
			Get();
			cost += X_COST; String s = t.val; 
			LogicalQubit bit = VarAccess();
			appendText(s + " " + logicalReg.getPhysical(bit));
			break;
		}
		case 10: {
			Get();
			cost += SWAP_COST; String s = t.val; 
			LogicalQubit bit1 = VarAccess();
			Expect(22);
			LogicalQubit bit2 = VarAccess();
			appendText(s + " " + logicalReg.getPhysical(bit1) + "," + logicalReg.getPhysical(bit2));
			break;
		}
		case 11: {
			Get();
			appendText(t.val + " ");
			LogicalQubit bit = VarAccess();
			appendText(logicalReg.getPhysical(bit));
			while (la.kind == 22) {
				Get();
				appendText(",");
				bit = VarAccess();
				appendText(logicalReg.getPhysical(bit));
			}
			break;
		}
		case 12: {
			Get();
			appendText(t.val + " ");
			LogicalQubit bit = VarAccess();
			appendText(logicalReg.getPhysical(bit));
			Expect(23);
			appendText(" " + t.val + " ");
			MeasurementBit mbit = VarMeasure();
			appendText(mbit.toString());
			break;
		}
		default: SynErr(26); break;
		}
		Expect(19);
		appendText(";\n");
	}

	String  Param() {
		String  val;
		val = "";
		String tmp; 
		if (la.kind == 1 || la.kind == 2 || la.kind == 3) {
			if (la.kind == 1) {
				Get();
				val = t.val; 
			} else if (la.kind == 2) {
				Get();
				val = t.val; 
			} else {
				Get();
				val = t.val; 
			}
			if (StartOf(2)) {
				tmp = Param();
				val += tmp; 
			}
		} else if (la.kind == 20) {
			Get();
			tmp = Param();
			val = tmp; 
			Expect(21);
		} else SynErr(27);
		return val;
	}

	LogicalQubit  VarAccess() {
		LogicalQubit  bit;
		Expect(1);
		String bitString = t.val; 
		if (la.kind == 17) {
			Get();
			Expect(2);
			bitString = bitString + "[" + t.val + "]"; 
			Expect(18);
		}
		bit = logicalReg.addQ(bitString);
		return bit;
	}

	MeasurementBit VarMeasure() {
		MeasurementBit bit;
		Expect(1);
		String bitString = t.val;
		if (la.kind == 17) {
			Get();
			Expect(2);
			bitString = bitString + "[" + t.val + "]";
			Expect(18);

		}
		bit = logicalReg.addC(bitString);
		return bit;
	}

	public int Parse() {
		la = new Token();
		la.val = "";		
		Get();
		QasmProgram();
		Expect(0);

		return cost;
	}

	private static final boolean[][] set = {
		{_T,_x,_x,_x, _x,_x,_x,_x, _x,_x,_x,_x, _x,_x,_x,_x, _x,_x,_x,_x, _x,_x,_x,_x, _x,_x},
		{_x,_x,_x,_x, _T,_T,_T,_T, _T,_T,_T,_T, _T,_x,_x,_x, _x,_x,_x,_x, _x,_x,_x,_x, _x,_x},
		{_x,_T,_T,_T, _x,_x,_x,_x, _x,_x,_x,_x, _x,_x,_x,_x, _x,_x,_x,_x, _T,_x,_x,_x, _x,_x}

	};
} // end SwappingParser


class Errors {
	public int count = 0;                                    // number of mappingErrors detected
	public java.io.PrintStream errorStream = System.out;     // error messages go to this stream
	public String errMsgFormat = "-- line {0} col {1}: {2}"; // 0=line, 1=column, 2=text
	
	protected void printMsg(int line, int column, String msg) {
		StringBuffer b = new StringBuffer(errMsgFormat);
		int pos = b.indexOf("{0}");
		if (pos >= 0) { b.delete(pos, pos+3); b.insert(pos, line); }
		pos = b.indexOf("{1}");
		if (pos >= 0) { b.delete(pos, pos+3); b.insert(pos, column); }
		pos = b.indexOf("{2}");
		if (pos >= 0) b.replace(pos, pos+3, msg);
		errorStream.println(b.toString());
	}
	
	public void SynErr (int line, int col, int n) {
		String s;
		switch (n) {
			case 0: s = "EOF expected"; break;
			case 1: s = "ident expected"; break;
			case 2: s = "index expected"; break;
			case 3: s = "other expected"; break;
			case 4: s = "qreg expected"; break;
			case 5: s = "creg expected"; break;
			case 6: s = "xgate expected"; break;
			case 7: s = "rzgate expected"; break;
			case 8: s = "sxgate expected"; break;
			case 9: s = "cxgate expected"; break;
			case 10: s = "swapgate expected"; break;
			case 11: s = "barrier expected"; break;
			case 12: s = "measure expected"; break;
			case 13: s = "\"OPENQASM\" expected"; break;
			case 14: s = "\"2.0;\" expected"; break;
			case 15: s = "\"include\" expected"; break;
			case 16: s = "\"\\\"qelib1.inc\\\";\" expected"; break;
			case 17: s = "\"[\" expected"; break;
			case 18: s = "\"]\" expected"; break;
			case 19: s = "\";\" expected"; break;
			case 20: s = "\"(\" expected"; break;
			case 21: s = "\")\" expected"; break;
			case 22: s = "\",\" expected"; break;
			case 23: s = "\"->\" expected"; break;
			case 24: s = "??? expected"; break;
			case 25: s = "invalid VarDecl"; break;
			case 26: s = "invalid Statement"; break;
			case 27: s = "invalid Param"; break;
			default: s = "error " + n; break;
		}
		printMsg(line, col, s);
		count++;
	}

	public void SemErr (int line, int col, String s) {	
		printMsg(line, col, s);
		count++;
	}
	
	public void SemErr (String s) {
		errorStream.println(s);
		count++;
	}
	
	public void Warning (int line, int col, String s) {	
		printMsg(line, col, s);
	}
	
	public void Warning (String s) {
		errorStream.println(s);
	}
} // MappingErrors


class FatalError extends RuntimeException {
	public static final long serialVersionUID = 1L;
	public FatalError(String s) { super(s); }
}

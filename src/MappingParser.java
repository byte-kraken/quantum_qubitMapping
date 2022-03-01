import kotlin.Pair;

public class MappingParser {
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
	public MappingErrors mappingErrors;

	LogicalReg reg;

	private final boolean DEBUG;

	public MappingParser(Scanner scanner, LogicalReg reg, boolean debug) {
		this.scanner = scanner;
		this.reg = reg;
		mappingErrors = new MappingErrors();

		this.DEBUG = debug;
	}

	void SynErr (int n) {
		if (errDist >= minErrDist) mappingErrors.SynErr(la.line, la.col, n);
		errDist = 0;
	}

	public void SemErr (String msg) {
		if (errDist >= minErrDist) mappingErrors.SemErr(t.line, t.col, msg);
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
		Expect(13);
		Expect(14);
		Expect(15);
		Expect(16);
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
			String regName = t.val;

			if (la.kind == 17) {
				Get();
				Expect(2);
				int regSize = Integer.parseInt(t.val);
				reg.getInitialRegs().add(new Pair(regName, regSize));
				Expect(18);
			}
		} else if (la.kind == 5) {
			Get();
			Expect(1);
			
			if (la.kind == 17) {
				Get();
				Expect(2);
				Expect(18);
			}
		} else SynErr(25);
		Expect(19);
	}

	void Statement() {
		switch (la.kind) {
		case 7: {
			Get();
			
			Expect(20);
			String val = Param();
			Expect(21);
			LogicalQubit bit = VarQubit();
			break;
		}
		case 8: {
			Get();

			LogicalQubit bit = VarQubit();
			break;
		}
		case 9: {
			Get();

			LogicalQubit bit1 = VarQubit();
			Expect(22);
			LogicalQubit bit2 = VarQubit();
			bit1.addOrUpdateEdge(bit2); 
			break;
		}
		case 6: {
			Get();

			LogicalQubit bit = VarQubit();
			break;
		}
		case 10: {
			Get();

			LogicalQubit bit = VarQubit();
			Expect(22);
			bit = VarQubit();
			break;
		}
		case 11: {
			Get();

			LogicalQubit bit = VarQubit();
			while (la.kind == 22) {
				Get();
				bit = VarQubit();
			}
			break;
		}
		case 12: {
			Get();

			LogicalQubit qubit = VarQubit();
			Expect(23);
			MeasurementBit mbit = VarMeasure();
			break;
		}
		default: SynErr(26); break;
		}
		Expect(19);
	}

	String Param() {
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

	LogicalQubit VarQubit() {
		Expect(1);
		String bitString = t.val;
		if (la.kind == 17) {
			Get();
			Expect(2);
			bitString = bitString + "[" + t.val + "]";
			Expect(18);

		}
		return reg.addQ(bitString);
	}

	MeasurementBit VarMeasure() {
		Expect(1);
		String bitString = t.val;
		if (la.kind == 17) {
			Get();
			Expect(2);
			bitString = bitString + "[" + t.val + "]";
			Expect(18);

		}
		return reg.addC(bitString);
	}



	public void Parse() {
		la = new Token();
		la.val = "";
		Get();
		QasmProgram();
		Expect(0);

	}

	private static final boolean[][] set = {
		{_T,_x,_x,_x, _x,_x,_x,_x, _x,_x,_x,_x, _x,_x,_x,_x, _x,_x,_x,_x, _x,_x,_x,_x, _x,_x},
		{_x,_x,_x,_x, _T,_T,_T,_T, _T,_T,_T,_T, _T,_x,_x,_x, _x,_x,_x,_x, _x,_x,_x,_x, _x,_x},
		{_x,_T,_T,_T, _x,_x,_x,_x, _x,_x,_x,_x, _x,_x,_x,_x, _x,_x,_x,_x, _T,_x,_x,_x, _x,_x}

	};
} // end SwappingParser


class MappingErrors {
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


class FatalMappingError extends RuntimeException {
	public static final long serialVersionUID = 1L;
	public FatalMappingError(String s) { super(s); }
}

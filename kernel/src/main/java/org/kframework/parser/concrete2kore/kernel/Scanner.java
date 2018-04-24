package org.kframework.parser.concrete2kore.kernel;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.kframework.attributes.Location;
import org.kframework.attributes.Source;
import org.kframework.definition.Module;
import org.kframework.definition.RegexTerminal;
import org.kframework.definition.Terminal;
import org.kframework.definition.TerminalLike;
import org.kframework.parser.concrete2kore.ParseInModule;
import org.kframework.utils.StringUtil;
import org.kframework.utils.OS;
import org.kframework.utils.errorsystem.KEMException;
import org.kframework.utils.errorsystem.KException;
import org.kframework.utils.errorsystem.ParseFailedException;
import scala.Tuple2;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Created by dwightguth on 7/21/16.
 */
public class Scanner implements AutoCloseable {

    private final Map<TerminalLike, Tuple2<Integer, Integer>> tokens;
    private final File scanner;
    private final Module module;

    private static final String FLEX_LIB = OS.current().equals(OS.OSX) ? "-ll" : "-lfl";

    private String layout;

    public Scanner(ParseInModule module) {
        this.tokens  = KSyntax2GrammarStatesFilter.getTokens(module.getParsingModule());
        this.module  = module.seedModule();
        String whites = "[\\ \\n\\r\\t]";
        if (this.module.layout().length() == 0) {
            this.layout = whites;
        } else {
            this.layout = this.module.layout() + "|(" + whites + ")";
        }
        this.scanner = getScanner();
    }

    public Module getModule() {
        return module;
    }

    // debugging method
    private TerminalLike getTokenByKind(int kind) {
        return tokens.entrySet().stream().filter(e -> e.getValue()._1() == kind).findAny().get().getKey();
    }

    public File getScanner() {
        File scanner;
        // tokenization
        try {
            File scannerSource = File.createTempFile("tmp-kompile-", ".l");
            scannerSource.deleteOnExit();
            StringBuilder flex = new StringBuilder();
            flex.append("%{\n" +
                    "#include<stdio.h>\n" +
                    "#include<stddef.h>\n" +
                    "#define ECHO do " +
                    " {" +
                    "   long long start_pos = yytext - buffer;" +
                    "   long long end_pos = start_pos + yyleng;" +
                    "   fwrite(&start_pos, sizeof(start_pos), 1, stdout);" +
                    "   fwrite(&end_pos, sizeof(end_pos), 1, stdout);" +
                    "   int kind = -1;" +
                    "   fwrite(&kind, sizeof(kind), 1, stdout);" +
                    "   int len = strlen(yytext);" +
                    "   fwrite(&len, sizeof(len), 1, stdout);" +
                    "   fwrite(yytext, 1, len, stdout);" +
                    " } while (0) \n" +
                    "char *buffer;\n" +
                    "%}\n" +
                    "%%\n" +
                    "(" + this.layout + ")" + " ;\n");
            List<TerminalLike> ordered = tokens.keySet().stream().sorted((t1, t2) -> tokens.get(t2)._2() - tokens.get(t1)._2()).collect(Collectors.toList());
            for (TerminalLike key : ordered) {
                if (key instanceof Terminal) {
                    Terminal t = (Terminal) key;
                    flex.append(StringUtil.enquoteCString(t.value()));
                } else {
                    RegexTerminal t = (RegexTerminal) key;
                    flex.append(t.regex());
                }
                writeAction(flex, key);
            }
            flex.append("%%\n" +
                    "int main(int argc, char **argv) {\n" +
                    "  freopen(NULL, \"rb\", stdin);\n" +
                    "  freopen(NULL, \"wb\", stdout);\n" +
                    "  while(1) {\n" +
                    "    int length;\n" +
                    "    size_t nread = fread(&length, sizeof(length), 1, stdin);\n" +
                    "    if (nread < 1) exit(0);\n" +
                    "    buffer = malloc(length + 2);\n" +
                    "    buffer[length] = 0;\n" +
                    "    buffer[length+1] = 0;\n" +
                    "    fread(buffer, length, 1, stdin);\n" +
                    "    YY_BUFFER_STATE bs = yy_scan_buffer(buffer, length + 2);\n" +
                    "    yy_switch_to_buffer(bs);\n" +
                    "    yylex();\n" +
                    "    long long exit = -1;\n" +
                    "    fwrite(&exit, sizeof(exit), 1, stdout);\n" +
                    "    fwrite(&exit, sizeof(exit), 1, stdout);\n" +
                    "    fwrite(&exit, sizeof(exit), 1, stdout);\n" +
                    "    fflush(stdout);\n" +
                    "  }\n" +
                    "}");
            FileUtils.write(scannerSource, flex);
            File scannerCSource = File.createTempFile("tmp-kompile-", ".c");
            scannerCSource.deleteOnExit();
            ProcessBuilder pb = new ProcessBuilder("flex", "--nowarn", "-Ca", "-o", scannerCSource.getAbsolutePath(), scannerSource.getAbsolutePath()).inheritIO();
            int exit = pb.start().waitFor();
            if (exit != 0) {
                throw KEMException.internalError("Flex returned nonzero exit code. See output for details.");
            }
            scanner = File.createTempFile("tmp-kompile-", "");
            scanner.deleteOnExit();
            pb = new ProcessBuilder("gcc", scannerCSource.getAbsolutePath(), "-o", scanner.getAbsolutePath(), FLEX_LIB);
            exit = pb.start().waitFor();
            scanner.setExecutable(true);
            if (exit != 0) {
                throw KEMException.internalError("gcc returned nonzero exit code. See output for details.");
            }
        } catch (IOException | InterruptedException e) {
            throw KEMException.internalError("Failed to write file for scanner", e);
        }
        return scanner;
    }

    private void writeAction(StringBuilder flex, TerminalLike key) {
        flex.append(" {\n" +
                "   long long start_pos = yytext - buffer;\n" +
                "   long long end_pos = start_pos + yyleng;\n" +
                "   fwrite(&start_pos, sizeof(start_pos), 1, stdout);\n" +
                "   fwrite(&end_pos, sizeof(end_pos), 1, stdout);\n" +
                "   int kind = ").append(tokens.get(key)._1()).append(";\n" +
                "   fwrite(&kind, sizeof(kind), 1, stdout);\n" +
                "   int len = strlen(yytext);\n" +
                "   fwrite(&len, sizeof(len), 1, stdout);\n" +
                "   fwrite(yytext, 1, len, stdout);\n" +
                " }\n");
    }

    private int maxToken = -1;

    public int getMaxToken() {
        int max = maxToken;
        if (max == -1) {
            for (Tuple2<Integer, Integer> val : tokens.values()) {
                max = Integer.max(max, val._1());
            }
            maxToken = max;
        }
        return max;
    }

    public int resolve(TerminalLike terminal) {
        return tokens.get(terminal)._1();
    }

    public static class Token {
        final int kind;
        final String value;
        final int startLoc;
        final int endLoc;

        public Token(int kind, String value, long startLoc, long endLoc) {
            this.kind = kind;
            this.value = value;
            assert startLoc < Integer.MAX_VALUE;
            assert endLoc < Integer.MAX_VALUE;
            this.startLoc = (int)startLoc;
            this.endLoc = (int)endLoc;
        }

        @Override
        public String toString() {
            return kind + ":" + value;
        }
    }

    @Override
    public void close() {
        synchronized(idleProcesses) {
            for (Process p : idleProcesses.get(this)) {
                p.destroy();
                cache.remove(p);
                activeProcceses--;
            }
            idleProcesses.removeAll(this);
        }
    }

    private static final int N_CPUS = Runtime.getRuntime().availableProcessors();
    private static final int N_PROCS = 512;
    private static int activeProcceses = 0;
    private static final Semaphore runningScanners = new Semaphore(N_PROCS);
    private static final ListMultimap<Scanner, Process> idleProcesses = ArrayListMultimap.create();
    private static final Map<Process, Scanner> cache = new LinkedHashMap<Process, Scanner>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Process, Scanner> entry) {
            if (activeProcceses > N_PROCS) {
                entry.getKey().destroy();
                idleProcesses.get(entry.getValue()).remove(entry.getKey());
                activeProcceses--;
                return true;
            }
            return false;
        }
    };

    public Token[] tokenize(String input, Source source, int[] lines, int[] columns) {
        try {
            runningScanners.acquire();

            Process process;
            synchronized (idleProcesses) {
                if (idleProcesses.get(this).size() > 0) {
                    List<Process> idleForThisScanner = idleProcesses.get(this);
                    process = idleForThisScanner.remove(idleForThisScanner.size() - 1);
                    cache.remove(process);
                } else {
                    process = new ProcessBuilder(scanner.getAbsolutePath()).start();
                    activeProcceses++;
                    // temporarily add it so that LinkedHashMap evicts the old entry
                    cache.put(process, this);
                    cache.remove(process);
                }
            }

            byte[] buf = input.getBytes("UTF-8");
            ByteBuffer size = ByteBuffer.allocate(4);
            size.order(ByteOrder.nativeOrder());
            size.putInt(buf.length + 1);
            process.getOutputStream().write(size.array());
            process.getOutputStream().write(buf);
            process.getOutputStream().write('\n');
            process.getOutputStream().flush();
            return readTokenizedOutput(process, source, lines, columns);
        } catch (IOException | InterruptedException e) {
            throw KEMException.internalError("Failed to invoke scanner", e);
        } finally {
            runningScanners.release();
        }
    }

    private Token[] readTokenizedOutput(Process process, Source source, int[] lines, int[] columns) throws IOException {
        List<Token> result = new ArrayList<>();
        boolean success = false;
        try {
            while (true) {
                byte[] buf = new byte[24];
                IOUtils.readFully(process.getInputStream(), buf);
                ByteBuffer byteBuf = ByteBuffer.wrap(buf);
                byteBuf.order(ByteOrder.nativeOrder());
                long startLoc = byteBuf.getLong();
                if (startLoc < 0) {
                    break;
                }
                long endLoc = byteBuf.getLong();
                int kind = byteBuf.getInt();
                int len = byteBuf.getInt();
                byte[] bytes = new byte[len];
                IOUtils.readFully(process.getInputStream(), bytes);
                String value = new String(bytes, "UTF-8");
                Token t = new Token(kind, value, startLoc, endLoc);
                if (kind == -1) {
                    String msg = "Scanner error: unexpected character sequence '" + value + "'.";
                    Location loc = new Location(lines[t.startLoc], columns[t.startLoc],
                            lines[t.endLoc], columns[t.endLoc]);
                    throw new ParseFailedException(new KException(
                            KException.ExceptionType.ERROR, KException.KExceptionGroup.INNER_PARSER, msg, source, loc));
                }
                result.add(t);
            }
            success = true;
            return result.toArray(new Token[result.size()]);
        } finally {
            if (success) {
                synchronized (idleProcesses) {
                    cache.put(process, this);
                    idleProcesses.put(this, process);
                }
            } else {
                // we aren't returning this process to the pool since something went wrong with it,
                // so we have to clean up here and then make sure that the pool knows it can allocate a new process.
                synchronized (idleProcesses) {
                    process.destroy();
                    activeProcceses--;
                }
            }
        }
    }

}

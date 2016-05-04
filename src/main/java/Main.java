import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import co.paralleluniverse.fibers.SuspendExecution;
import co.paralleluniverse.fibers.Suspendable;
import co.paralleluniverse.strands.Strand;
import co.paralleluniverse.strands.channels.*;
import co.paralleluniverse.strands.concurrent.ReentrantLock;

public final class Main {
    private static final int RUNS = 1000;
    private static final int PRODUCERS = 128;
    private static final int CHANNEL_BUFFER = 0;
    private static final int MESSAGES_PER_PRODUCER = 10;

    private static final long PROD_SLEEP_MS = 0;
    private static final long CONS_SLEEP_MS = 0;

    private static final long CONS_SELECT_TIMEOUT_MS = 1000;

    private static ReentrantLock l = new ReentrantLock();
    @Suspendable
    private static void l(String format, Object... args) {
        l.lock();
        try {
            final PrintStream e = System.err;
            e.print("[" + Strand.currentStrand().getName() + "] ");
            e.printf(format, args);
            e.println();
            e.flush();
        } finally {
            l.unlock();
        }
    }

    public static void main(String[] args) throws ExecutionException, InterruptedException {
        for (int k = 0 ; k < RUNS ; k++) {
            System.err.println();
            l("STARTING ITERATION %d", k);
            final Channel[] chs = new Channel[PRODUCERS];
            final Strand[] prod = new Strand[PRODUCERS];
            for (int i = 0; i < PRODUCERS; i++) {
                chs[i] = Channels.newChannel(CHANNEL_BUFFER, Channels.OverflowPolicy.BLOCK, true, true);
                final int iF = i;
                prod[i] = Strand.of(new Thread(new Runnable() {
                    @Override
                    @Suspendable
                    public void run() {
                        try {
                            for (int j = 0; j < MESSAGES_PER_PRODUCER; j++) {
                                //noinspection ConstantConditions
                                if (PROD_SLEEP_MS > 0) {
                                    l("sleeping %dms before send", PROD_SLEEP_MS);
                                    Strand.sleep(PROD_SLEEP_MS);
                                }
                                final String s = Strand.currentStrand().getName() + ": '" + Integer.toString(j) + "'";
                                l("sending: \"%s\"", s);
                                //noinspection unchecked
                                chs[iF].send(s);
                                l("sent: \"%s\"", s);
                            }
                        } catch (final SuspendExecution | InterruptedException e) {
                            l("!!! caught %s with msg '%s', retrowing as assert failure (trace follows)", e.getClass().getName(), e.getMessage());
                            e.printStackTrace(System.err);
                            throw new AssertionError(e);
                        } finally {
                            l("closing channel");
                            chs[iF].close();
                            l("exiting");
                        }
                    }
                }));
                final String n = "prod" + Integer.toString(i);
                l("starting \"%s\"", n);
                prod[i].setName(n);
                prod[i].start();
            }

            final Strand dst = Strand.of(new Thread(new Runnable() {
                @Override
                @Suspendable
                public void run() {
                    try {
                        final List<Port> done = new ArrayList<>(PRODUCERS);
                        while (true) {
                            l("building select with open channels");
                            final StringBuilder added = new StringBuilder();
                            final List<SelectAction<Object>> sas = new ArrayList<>(PRODUCERS);

                            boolean first = true;
                            for (int i = 0; i < PRODUCERS; i++) {
                                if (!done.contains(chs[i])) {
                                    if (!first) {
                                        added.append(",");
                                    }
                                    first = false;
                                    //noinspection unchecked
                                    sas.add(Selector.receive(chs[i]));
                                    added.append(Integer.toString(i));
                                }
                            }
                            l("added channels %s", added.toString());

                            if (sas.size() == 0) {
                                l("all channels closed, exiting");
                                return;
                            }

                            //noinspection ConstantConditions
                            if (CONS_SLEEP_MS > 0) {
                                l("sleeping %dms before select", CONS_SLEEP_MS);
                                Strand.sleep(CONS_SLEEP_MS);
                            }
                            l("selecting with %dms timeout", CONS_SELECT_TIMEOUT_MS);
                            final SelectAction m = Selector.select(CONS_SELECT_TIMEOUT_MS, TimeUnit.MILLISECONDS, sas);
                            sas.clear();

                            if (m == null) {
                                l("select timed out, exiting");
                                return;
                            }

                            final Object msg = m.message();
                            if (msg != null) {
                                l("select returned: \"%s\"", msg);
                            } else {
                                if (m.port() != null) {
                                    final Port p = m.port();
                                    if (m.port() instanceof ReceivePort) {
                                        final ReceivePort rp = (ReceivePort) p;
                                        if (rp.isClosed()) {
                                            l("select returned `null` from closed receive channel with index %d in the list, excluding", m.index());
                                            done.add(rp);
                                        } else
                                            l("!!!ERROR: select returned `null` from OPEN receive channel with index %d in the list!!!", m.index());
                                    } else {
                                        l("!!!ERROR: select returned `null` from non-receive channel with index %d in the list!!!", m.index());
                                    }
                                } else {
                                    l("!!!ERROR: select returned `null` from `null` channel with index %d in the list!!!", m.index());
                                }
                            }
                        }
                    } catch (final SuspendExecution | InterruptedException e) {
                        l("!!! caught %s with msg '%s', re-trowing as assert failure (trace follows)", e.getClass().getName(), e.getMessage());
                        e.printStackTrace(System.err);
                        throw new AssertionError(e);
                    }
                }
            }));

            final String n = "cons";
            l("starting \"%s\"", n);
            dst.setName(n);
            dst.start();

            for (int i = 0; i < PRODUCERS; i++) {
                final Strand s = prod[i];
                final String np = s.getName();
                l("joining \"%s\"", np);
                l("joined \"%s\"", np);
                s.join();
            }
            l("joining \"%s\"", n);
            dst.join();
            l("joined \"%s\"", n);
        }
    }
}

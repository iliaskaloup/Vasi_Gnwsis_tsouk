/*
 * The MIT License
 *
 * Copyright (c) 2004-2009, Sun Microsystems, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package hudson.cli.declarative;

import hudson.Extension;
import hudson.ExtensionComponent;
import hudson.ExtensionFinder;
import hudson.Util;
import hudson.cli.CLICommand;
import hudson.cli.CloneableCLICommand;
import hudson.model.Hudson;
import hudson.remoting.Channel;
import hudson.security.CliAuthenticator;
import org.acegisecurity.Authentication;
import org.acegisecurity.context.SecurityContext;
import org.acegisecurity.context.SecurityContextHolder;
import org.jvnet.hudson.annotation_indexer.Index;
import org.jvnet.localizer.ResourceBundleHolder;
import org.kohsuke.args4j.ClassParser;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.CmdLineException;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Stack;
import static java.util.logging.Level.SEVERE;
import java.util.logging.Logger;

/**
 * Discover {@link CLIMethod}s and register them as {@link CLICommand} implementations.
 *
 * @author Kohsuke Kawaguchi
 */
@Extension
public class CLIRegisterer extends ExtensionFinder {
    public <T> Collection<ExtensionComponent<T>> find(Class<T> type, Hudson hudson) {
        if (type==CLICommand.class)
            return (List)discover(hudson);
        else
            return Collections.emptyList();
    }

    /**
     * Finds a resolved method annotated with {@link CLIResolver}.
     */
    private Method findResolver(Class type) throws IOException {
        List<Method> resolvers = Util.filter(Index.list(CLIResolver.class, Hudson.getInstance().getPluginManager().uberClassLoader), Method.class);
        for ( ; type!=null; type=type.getSuperclass())
            for (Method m : resolvers)
                if (m.getReturnType()==type)
                    return m;
        return null;
    }

    private List<ExtensionComponent<CLICommand>> discover(final Hudson hudson) {
        LOGGER.fine("Listing up @CLIMethod");
        List<ExtensionComponent<CLICommand>> r = new ArrayList<ExtensionComponent<CLICommand>>();

        try {
            for ( final Method m : Util.filter(Index.list(CLIMethod.class, hudson.getPluginManager().uberClassLoader),Method.class)) {
                try {
                    // command name
                    final String name = m.getAnnotation(CLIMethod.class).name();

                    final ResourceBundleHolder res = loadMessageBundle(m);
                    res.format("CLI."+name+".shortDescription");   // make sure we have the resource, to fail early

                    r.add(new ExtensionComponent<CLICommand>(new CloneableCLICommand() {
                        @Override
                        public String getName() {
                            return name;
                        }

                        public String getShortDescription() {
                            // format by using the right locale
                            return res.format("CLI."+name+".shortDescription");
                        }

                        @Override
                        public int main(List<String> args, Locale locale, InputStream stdin, PrintStream stdout, PrintStream stderr) {
                            this.stdout = stdout;
                            this.stderr = stderr;
                            this.locale = locale;
                            this.channel = Channel.current();

                            registerOptionHandlers();
                            CmdLineParser parser = new CmdLineParser(null);
                            try {
                                SecurityContext sc = SecurityContextHolder.getContext();
                                Authentication old = sc.getAuthentication();
                                try {
                                    //  build up the call sequence
                                    Stack<Method> chains = new Stack<Method>();
                                    Method method = m;
                                    while (true) {
                                        chains.push(method);
                                        if (Modifier.isStatic(method.getModifiers()))
                                            break; // the chain is complete.

                                        // the method in question is an instance method, so we need to resolve the instance by using another resolver
                                        Class<?> type = method.getDeclaringClass();
                                        method = findResolver(type);
                                        if (method==null) {
                                            stderr.println("Unable to find the resolver method annotated with @CLIResolver for "+type);
                                            return 1;
                                        }
                                    }

                                    List<MethodBinder> binders = new ArrayList<MethodBinder>();

                                    while (!chains.isEmpty())
                                        binders.add(new MethodBinder(chains.pop(),parser));

                                    // authentication
                                    CliAuthenticator authenticator = Hudson.getInstance().getSecurityRealm().createCliAuthenticator(this);
                                    new ClassParser().parse(authenticator,parser);

                                    // fill up all the binders
                                    parser.parseArgument(args);

                                    sc.setAuthentication(authenticator.authenticate()); // run the CLI with the right credential

                                    // resolve them
                                    Object instance = null;
                                    for (MethodBinder binder : binders)
                                        instance = binder.call(instance);

                                    if (instance instanceof Integer)
                                        return (Integer) instance;
                                    else
                                        return 0;
                                } catch (InvocationTargetException e) {
                                    Throwable t = e.getTargetException();
                                    if (t instanceof Exception)
                                        throw (Exception) t;
                                    throw e;
                                } finally {
                                    sc.setAuthentication(old); // restore
                                }
                            } catch (CmdLineException e) {
                                stderr.println(e.getMessage());
                                printUsage(stderr,parser);
                                return 1;
                            } catch (Exception e) {
                                e.printStackTrace(stderr);
                                return 1;
                            }
                        }

                        protected int run() throws Exception {
                            throw new UnsupportedOperationException();
                        }
                    }));
                } catch (ClassNotFoundException e) {
                    LOGGER.log(SEVERE,"Failed to process @CLIMethod: "+m,e);
                }
            }
        } catch (IOException e) {
            LOGGER.log(SEVERE, "Failed to discvoer @CLIMethod",e);
        }

        return r;
    }

    /**
     * Locates the {@link ResourceBundleHolder} for this CLI method.
     */
    private ResourceBundleHolder loadMessageBundle(Method m) throws ClassNotFoundException {
        Class c = m.getDeclaringClass();
        Class<?> msg = c.getClassLoader().loadClass(c.getName().substring(0, c.getName().lastIndexOf(".")) + ".Messages");
        return ResourceBundleHolder.get(msg);
    }

    private static final Logger LOGGER = Logger.getLogger(CLIRegisterer.class.getName());
}
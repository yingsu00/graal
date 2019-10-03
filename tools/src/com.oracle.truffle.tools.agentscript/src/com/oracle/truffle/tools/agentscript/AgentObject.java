/*
 * Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.truffle.tools.agentscript;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.instrumentation.EventContext;
import com.oracle.truffle.api.instrumentation.ExecutionEventNode;
import com.oracle.truffle.api.instrumentation.ExecutionEventNodeFactory;
import com.oracle.truffle.api.instrumentation.Instrumenter;
import com.oracle.truffle.api.instrumentation.LoadSourceEvent;
import com.oracle.truffle.api.instrumentation.LoadSourceListener;
import com.oracle.truffle.api.instrumentation.SourceFilter;
import com.oracle.truffle.api.instrumentation.SourceSectionFilter;
import com.oracle.truffle.api.instrumentation.Tag;
import com.oracle.truffle.api.interop.InteropException;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.InvalidArrayIndexException;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnknownIdentifierException;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.library.CachedLibrary;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import com.oracle.truffle.api.nodes.LanguageInfo;
import com.oracle.truffle.api.source.Source;
import java.util.concurrent.atomic.AtomicBoolean;

@ExportLibrary(InteropLibrary.class)
final class AgentObject implements TruffleObject {
    private final Instrumenter instrumenter;
    private final LanguageInfo language;
    private final AtomicBoolean initializationFinished;

    AgentObject(Instrumenter instrumenter, Source script, LanguageInfo language) {
        this.instrumenter = instrumenter;
        this.language = language;
        this.initializationFinished = new AtomicBoolean(false);
    }

    private static final class ExcludeAgentScriptsFilter implements SourceSectionFilter.SourcePredicate {
        private final AtomicBoolean initializationFinished;

        ExcludeAgentScriptsFilter(AtomicBoolean initializationFinished) {
            this.initializationFinished = initializationFinished;
        }

        @Override
        public boolean test(Source source) {
            return initializationFinished.get();
        }
    }

    @ExportMessage
    static boolean hasMembers(AgentObject obj) {
        return true;
    }

    @ExportMessage
    static Object getMembers(AgentObject obj, boolean includeInternal) {
        return new Object[0];
    }

    @CompilerDirectives.TruffleBoundary
    @ExportMessage
    static Object invokeMember(AgentObject obj, String member, Object[] args,
                    @CachedLibrary(limit = "1") InteropLibrary interop) throws UnknownIdentifierException, UnsupportedMessageException {
        switch (member) {
            case "on":
                AgentType type = AgentType.find((String) args[0]);
                switch (type) {
                    case SOURCE: {
                        SourceFilter filter = SourceFilter.newBuilder().sourceIs(new ExcludeAgentScriptsFilter(obj.initializationFinished)).includeInternal(false).build();
                        obj.instrumenter.attachLoadSourceListener(filter, new LoadSourceListener() {
                            @Override
                            public void onLoad(LoadSourceEvent event) {
                                try {
                                    interop.execute(args[1], new SourceEventObject(event.getSource()));
                                } catch (InteropException ex) {
                                    throw raise(RuntimeException.class, ex);
                                }
                            }
                        }, true);
                        break;
                    }
                    case ENTER: {
                        CompilerDirectives.transferToInterpreter();
                        final SourceSectionFilter.Builder builder = SourceSectionFilter.newBuilder().sourceIs(new ExcludeAgentScriptsFilter(obj.initializationFinished)).includeInternal(false);
                        final InteropLibrary iop = InteropLibrary.getFactory().getUncached();
                        Object tags = iop.readMember(args[2], "tags");
                        if (iop.hasArrayElements(tags)) {
                            long tagsCount = (int) iop.getArraySize(tags);
                            for (long i = 0; i < tagsCount; i++) {
                                Object ith = null;
                                try {
                                    ith = iop.readArrayElement(tags, i);
                                } catch (InvalidArrayIndexException ex) {
                                    ex.printStackTrace();
                                }
                                Class<? extends Tag> tagClass = Tag.findProvidedTag(obj.language, (String) ith);
                                builder.tagIs(tagClass);
                            }
                        }

                        final SourceSectionFilter filter = builder.build();
                        obj.instrumenter.attachExecutionEventFactory(filter, new ExecutionEventNodeFactory() {
                            @Override
                            public ExecutionEventNode create(EventContext context) {
                                final EventContextObject ctx = new EventContextObject(context);
                                return new ExecutionEventNode() {
                                    @Child private InteropLibrary dispatch = InteropLibrary.getFactory().createDispatched(3);

                                    @Override
                                    protected void onEnter(VirtualFrame frame) {
                                        try {
                                            dispatch.execute(args[1], ctx);
                                        } catch (InteropException ex) {
                                            throw raise(RuntimeException.class, ex);
                                        }
                                    }
                                };
                            }
                        });
                        break;
                    }

                    default:
                        throw new IllegalStateException();
                }
                break;
            default:
                throw UnknownIdentifierException.create(member);
        }
        return obj;
    }

    @ExportMessage
    static boolean isMemberInvocable(AgentObject obj, String member) {
        return false;
    }

    void initializationFinished() {
        this.initializationFinished.set(true);
    }

    @SuppressWarnings("unchecked")
    static <T extends Exception> T raise(Class<T> type, Exception ex) throws T {
        throw (T) ex;
    }
}

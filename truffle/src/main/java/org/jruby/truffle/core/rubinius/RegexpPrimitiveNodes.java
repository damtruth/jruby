/*
 * Copyright (c) 2015, 2016 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 1.0
 * GNU General Public License version 2
 * GNU Lesser General Public License version 2.1
 */
package org.jruby.truffle.core.rubinius;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.ImportStatic;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.object.DynamicObject;
import org.jcodings.Encoding;
import org.joni.Matcher;
import org.joni.Regex;
import org.jruby.truffle.Layouts;
import org.jruby.truffle.RubyContext;
import org.jruby.truffle.builtins.Primitive;
import org.jruby.truffle.builtins.PrimitiveArrayArgumentsNode;
import org.jruby.truffle.core.regexp.RegexpGuards;
import org.jruby.truffle.core.regexp.RegexpNodes;
import org.jruby.truffle.core.rope.Rope;
import org.jruby.truffle.core.rope.RopeNodes;
import org.jruby.truffle.core.rope.RopeOperations;
import org.jruby.truffle.core.string.StringOperations;
import org.jruby.truffle.language.control.RaiseException;
import org.jruby.util.ByteList;
import org.jruby.util.RegexpSupport;

/**
 * Rubinius primitives associated with the Ruby {@code Regexp} class.

 */
public abstract class RegexpPrimitiveNodes {

    @Primitive(name = "regexp_fixed_encoding_p")
    public static abstract class RegexpFixedEncodingPrimitiveNode extends PrimitiveArrayArgumentsNode {

        @Specialization
        public boolean fixedEncoding(DynamicObject regexp) {
            return Layouts.REGEXP.getOptions(regexp).isFixed();
        }

    }

    @Primitive(name = "regexp_initialize", lowerFixnumParameters = 1)
    @ImportStatic(RegexpGuards.class)
    public static abstract class RegexpInitializePrimitiveNode extends PrimitiveArrayArgumentsNode {

        @Specialization(guards = {"isRegexpLiteral(regexp)", "isRubyString(pattern)"})
        public DynamicObject initializeRegexpLiteral(DynamicObject regexp, DynamicObject pattern, int options) {
            throw new RaiseException(coreExceptions().securityError("can't modify literal regexp", this));
        }

        @Specialization(guards = {"!isRegexpLiteral(regexp)", "isInitialized(regexp)", "isRubyString(pattern)"})
        public DynamicObject initializeAlreadyInitialized(DynamicObject regexp, DynamicObject pattern, int options) {
            throw new RaiseException(coreExceptions().typeError("already initialized regexp", this));
        }

        @Specialization(guards = {"!isRegexpLiteral(regexp)", "!isInitialized(regexp)", "isRubyString(pattern)"})
        public DynamicObject initialize(DynamicObject regexp, DynamicObject pattern, int options) {
            RegexpNodes.initialize(getContext(), regexp, this, StringOperations.rope(pattern), options);
            return regexp;
        }

    }

    @Primitive(name = "regexp_options")
    @ImportStatic(RegexpGuards.class)
    public static abstract class RegexpOptionsPrimitiveNode extends PrimitiveArrayArgumentsNode {

        @Specialization(guards = "isInitialized(regexp)")
        public int options(DynamicObject regexp) {
            return Layouts.REGEXP.getOptions(regexp).toOptions();
        }

        @Specialization(guards = "!isInitialized(regexp)")
        public int optionsNotInitialized(DynamicObject regexp) {
            throw new RaiseException(coreExceptions().typeError("uninitialized Regexp", this));
        }

    }

    @Primitive(name = "regexp_propagate_last_match")
    public static abstract class RegexpPropagateLastMatchPrimitiveNode extends PrimitiveArrayArgumentsNode {

        @Specialization
        public DynamicObject propagateLastMatch(DynamicObject regexpClass) {
            // TODO (nirvdrum 08-Jun-15): This method seems to exist just to fix Rubinius's broken frame-local scoping.  This assertion needs to be verified, however.
            return nil();
        }

    }

    @Primitive(name = "regexp_search_region", lowerFixnumParameters = {1, 2})
    @ImportStatic(RegexpGuards.class)
    public static abstract class RegexpSearchRegionPrimitiveNode extends PrimitiveArrayArgumentsNode {

        @Specialization(guards = {"!isInitialized(regexp)", "isRubyString(string)"})
        public Object searchRegionNotInitialized(DynamicObject regexp, DynamicObject string, int start, int end, boolean forward) {
            throw new RaiseException(coreExceptions().typeError("uninitialized Regexp", this));
        }

        @Specialization(guards = {"isRubyString(string)", "!isValidEncoding(string)"})
        public Object searchRegionInvalidEncoding(DynamicObject regexp, DynamicObject string, int start, int end, boolean forward) {
            throw new RaiseException(coreExceptions().argumentError(formatError(string), this));
        }

        @TruffleBoundary
        private String formatError(DynamicObject string) {
            return String.format("invalid byte sequence in %s", Layouts.STRING.getRope(string).getEncoding());
        }

        @TruffleBoundary
        @Specialization(guards = {"isInitialized(regexp)", "isRubyString(string)", "isValidEncoding(string)"})
        public Object searchRegion(DynamicObject regexp, DynamicObject string, int start, int end, boolean forward,
                                   @Cached("createX()") RopeNodes.MakeSubstringNode makeSubstringNode) {
            final Rope stringRope = StringOperations.rope(string);
            final Rope regexpSourceRope = Layouts.REGEXP.getSource(regexp);
            final Encoding enc = RegexpNodes.checkEncoding(regexp, stringRope, true);
            ByteList preprocessed = RegexpSupport.preprocess(getContext().getJRubyRuntime(), RopeOperations.getByteListReadOnly(regexpSourceRope), enc, new Encoding[]{null}, RegexpSupport.ErrorMode.RAISE);
            Rope preprocessedRope = RegexpNodes.shimModifiers(StringOperations.ropeFromByteList(preprocessed));
            final Regex r = new Regex(preprocessedRope.getBytes(), 0, preprocessedRope.byteLength(), Layouts.REGEXP.getRegex(regexp).getOptions(), RegexpNodes.checkEncoding(regexp, stringRope, true));
            final Matcher matcher = r.matcher(stringRope.getBytes(), 0, stringRope.byteLength());

            if (forward) {
                // Search forward through the string.
                return RegexpNodes.matchCommon(getContext(), makeSubstringNode, regexp, string, false, false, matcher, start, end);
            } else {
                // Search backward through the string.
                return RegexpNodes.matchCommon(getContext(), makeSubstringNode, regexp, string, false, false, matcher, end, start);
            }
        }

    }

    @Primitive(name = "regexp_set_last_match")
    public static abstract class RegexpSetLastMatchPrimitiveNode extends PrimitiveArrayArgumentsNode {

        @Specialization
        public Object setLastMatchData(DynamicObject regexpClass, Object matchData) {
            setLastMatch(getContext(), matchData);
            return matchData;
        }

        @TruffleBoundary
        public static void setLastMatch(RubyContext context, Object matchData) {
            final DynamicObject threadLocals = Layouts.THREAD.getThreadLocals(context.getThreadManager().getCurrentThread());
            threadLocals.set("$~", matchData);
        }

    }

    @Primitive(name = "regexp_set_block_last_match")
    public static abstract class RegexpSetBlockLastMatchPrimitiveNode extends PrimitiveArrayArgumentsNode {

        @Specialization
        public DynamicObject setBlockLastMatch(DynamicObject regexpClass) {
            // TODO CS 7-Mar-15 what does this do?
            return nil();
        }

    }
    
}

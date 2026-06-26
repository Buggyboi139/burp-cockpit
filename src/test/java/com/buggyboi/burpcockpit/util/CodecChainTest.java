package com.buggyboi.burpcockpit.util;

import java.util.List;

public final class CodecChainTest {
    public static void main(String[] args) {
        singleUrlEncoding();
        singleBase64Encoding();
        singleBase64UrlEncoding();
        nestedUrlThenBase64ThenUrl();
        plusFormSpaceUrlEncoding();
        base64WithPlusIsNotTreatedAsFormSpace();
        plainInputProducesNoChain();
        invalidBase64LookingInputDoesNotDecode();
        editedDecodedValueReencodesThroughSameChain();
        nestedTraceMirrorsDecodePlainEncode();
    }

    private static void singleUrlEncoding() {
        CodecChain.Result result = CodecChain.decode("hello%20world%21");
        assertEquals("hello world!", result.decoded(), "URL decoded text");
        assertKinds(result, CodecChain.Kind.URL);
        assertEquals("hello%20there%21", result.reencode("hello there!"), "URL re-encoded text");
    }

    private static void singleBase64Encoding() {
        CodecChain.Result result = CodecChain.decode("cGF5bG9hZA==");
        assertEquals("payload", result.decoded(), "Base64 decoded text");
        assertKinds(result, CodecChain.Kind.BASE64);
        assertEquals("YWRtaW4=", result.reencode("admin"), "Base64 re-encoded text");
    }

    private static void singleBase64UrlEncoding() {
        CodecChain.Result result = CodecChain.decode("Pz8_");
        assertEquals("???", result.decoded(), "Base64URL decoded text");
        assertKinds(result, CodecChain.Kind.BASE64URL);
        assertEquals("Pz8_", result.reencode("???"), "Base64URL re-encoded text");
    }

    private static void nestedUrlThenBase64ThenUrl() {
        CodecChain.Result result = CodecChain.decode("aGVsbG8rd29ybGQ%3D");
        assertEquals("hello world", result.decoded(), "Nested decoded text");
        assertKinds(result, CodecChain.Kind.URL, CodecChain.Kind.BASE64, CodecChain.Kind.URL);
    }

    private static void plusFormSpaceUrlEncoding() {
        CodecChain.Result result = CodecChain.decode("hello+world");
        assertEquals("hello world", result.decoded(), "Plus form-space decoded text");
        assertKinds(result, CodecChain.Kind.URL);
        assertEquals("hello+there", result.reencode("hello there"), "Plus form-space re-encoded text");
    }

    private static void base64WithPlusIsNotTreatedAsFormSpace() {
        CodecChain.Result result = CodecChain.decode("8J+YgA==");
        assertEquals("\uD83D\uDE00", result.decoded(), "Base64 with plus decoded text");
        assertKinds(result, CodecChain.Kind.BASE64);
    }

    private static void plainInputProducesNoChain() {
        CodecChain.Result result = CodecChain.decode("plain value 123");
        assertEquals("plain value 123", result.decoded(), "Plain decoded text");
        assertKinds(result);
    }

    private static void invalidBase64LookingInputDoesNotDecode() {
        CodecChain.Result result = CodecChain.decode("abcd");
        assertEquals("abcd", result.decoded(), "Invalid Base64-looking text");
        assertKinds(result);
    }

    private static void editedDecodedValueReencodesThroughSameChain() {
        CodecChain.Result result = CodecChain.decode("aGVsbG8rd29ybGQ%3D");
        String edited = result.reencode("hello there");
        CodecChain.Result roundTrip = CodecChain.decode(edited);
        assertEquals("hello there", roundTrip.decoded(), "Edited round trip decoded text");
        assertKinds(roundTrip, CodecChain.Kind.URL, CodecChain.Kind.BASE64, CodecChain.Kind.URL);
    }

    private static void nestedTraceMirrorsDecodePlainEncode() {
        CodecChain.Result result = CodecChain.decode("aGVsbG8rd29ybGQ%3D");

        List<CodecChain.Layer> decodeLayers = result.decodeLayers();
        assertEquals(3, decodeLayers.size(), "decode layer count");
        assertEquals("aGVsbG8rd29ybGQ=", decodeLayers.get(0).output(), "first decode layer");
        assertEquals("hello+world", decodeLayers.get(1).output(), "second decode layer");
        assertEquals("hello world", decodeLayers.get(2).output(), "plain decode layer");

        List<CodecChain.Layer> encodeLayers = result.encodeLayers("hello there");
        assertEquals(3, encodeLayers.size(), "encode layer count");
        assertEquals("hello+there", encodeLayers.get(0).output(), "first encode layer");
        assertEquals("aGVsbG8rdGhlcmU=", encodeLayers.get(1).output(), "second encode layer");
        assertEquals("aGVsbG8rdGhlcmU%3D", encodeLayers.get(2).output(), "final encode layer");
        assertEquals("URL decode -> Base64 decode -> URL decode -> plain -> URL encode -> Base64 encode -> URL encode",
                result.sandwichDisplay(), "sandwich display");
    }

    private static void assertKinds(CodecChain.Result result, CodecChain.Kind... expected) {
        List<CodecChain.Step> steps = result.steps();
        if (steps.size() != expected.length) {
            throw new AssertionError("Expected " + expected.length + " steps but got " + steps.size() + ": " + result.chainDisplay());
        }
        for (int i = 0; i < expected.length; i++) {
            if (steps.get(i).kind() != expected[i]) {
                throw new AssertionError("Expected step " + i + " to be " + expected[i] + " but got " + steps.get(i).kind());
            }
        }
    }

    private static void assertEquals(String expected, String actual, String label) {
        if (!expected.equals(actual)) {
            throw new AssertionError(label + ": expected [" + expected + "] but got [" + actual + "]");
        }
    }

    private static void assertEquals(int expected, int actual, String label) {
        if (expected != actual) {
            throw new AssertionError(label + ": expected [" + expected + "] but got [" + actual + "]");
        }
    }
}

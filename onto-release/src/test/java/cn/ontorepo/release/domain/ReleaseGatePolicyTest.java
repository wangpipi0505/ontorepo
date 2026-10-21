package cn.ontorepo.release.domain;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

class ReleaseGatePolicyTest {
    private static final String SHA = "a".repeat(64);
    private static final String OTHER = "b".repeat(64);

    private List<ReleaseGatePolicy.Check> passed() {
        return new ArrayList<>(
                java.util.Arrays.stream(ReleaseGatePolicy.Kind.values())
                        .map(
                                k ->
                                        new ReleaseGatePolicy.Check(
                                                k, SHA, ReleaseGatePolicy.Conclusion.PASSED, null))
                        .toList());
    }

    @Test
    void allFiveRealGatesAndCurrentRevisionAllowPreparation() {
        assertTrue(ReleaseGatePolicy.evaluate(SHA, 8, 8, passed(), 3, null).allowed());
    }

    @Test
    void draftChangeAndForeignTestDigestCannotReuseReports() {
        assertFalse(ReleaseGatePolicy.evaluate(SHA, 8, 9, passed(), 3, null).allowed());
        var checks = passed();
        checks.set(
                3,
                new ReleaseGatePolicy.Check(
                        ReleaseGatePolicy.Kind.TEST,
                        OTHER,
                        ReleaseGatePolicy.Conclusion.PASSED,
                        null));
        assertFalse(ReleaseGatePolicy.evaluate(SHA, 8, 8, checks, 3, null).allowed());
    }

    @Test
    void missingDuplicateOrZeroCaseReportsDoNotPass() {
        var checks = passed();
        checks.removeLast();
        assertFalse(ReleaseGatePolicy.evaluate(SHA, 8, 8, checks, 3, null).allowed());
        checks = passed();
        checks.add(checks.getFirst());
        assertFalse(ReleaseGatePolicy.evaluate(SHA, 8, 8, checks, 3, null).allowed());
        assertFalse(ReleaseGatePolicy.evaluate(SHA, 8, 8, passed(), 0, null).allowed());
    }

    @Test
    void onlyOwnerWaiverBoundToSameDigestAndRevisionCanCoverSubset() {
        var checks = passed();
        checks.set(
                1,
                new ReleaseGatePolicy.Check(
                        ReleaseGatePolicy.Kind.CONSISTENCY,
                        SHA,
                        ReleaseGatePolicy.Conclusion.NOT_CHECKED,
                        "SUBSET_EXCEEDED"));
        assertFalse(ReleaseGatePolicy.evaluate(SHA, 8, 8, checks, 3, null).allowed());
        var accepted = new ReleaseGatePolicy.Waiver("OWNER", SHA, 8, "已人工核对完整公理");
        assertTrue(ReleaseGatePolicy.evaluate(SHA, 8, 8, checks, 3, accepted).allowed());
        for (var invalid :
                List.of(
                        new ReleaseGatePolicy.Waiver("EDITOR", SHA, 8, "理由"),
                        new ReleaseGatePolicy.Waiver("OWNER", OTHER, 8, "理由"),
                        new ReleaseGatePolicy.Waiver("OWNER", SHA, 7, "理由"),
                        new ReleaseGatePolicy.Waiver("OWNER", SHA, 8, " "))) {
            assertFalse(ReleaseGatePolicy.evaluate(SHA, 8, 8, checks, 3, invalid).allowed());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"TIMEOUT", "ENGINE_ERROR", "INCONSISTENT", "GUARD_ATTR_UNRESOLVED"})
    void ownerWaiverCannotCoverOtherFailureReasons(String reason) {
        var checks = passed();
        checks.set(
                1,
                new ReleaseGatePolicy.Check(
                        ReleaseGatePolicy.Kind.CONSISTENCY,
                        SHA,
                        ReleaseGatePolicy.Conclusion.FAILED,
                        reason));
        var waiver = new ReleaseGatePolicy.Waiver("OWNER", SHA, 8, "人工确认");
        assertFalse(ReleaseGatePolicy.evaluate(SHA, 8, 8, checks, 3, waiver).allowed());
        checks.set(
                1,
                new ReleaseGatePolicy.Check(
                        ReleaseGatePolicy.Kind.CONSISTENCY,
                        SHA,
                        ReleaseGatePolicy.Conclusion.NOT_CHECKED,
                        reason));
        assertFalse(ReleaseGatePolicy.evaluate(SHA, 8, 8, checks, 3, waiver).allowed());
    }

    @Test
    void subsetReasonInContractGateIsNeverWaivable() {
        var checks = passed();
        checks.set(
                2,
                new ReleaseGatePolicy.Check(
                        ReleaseGatePolicy.Kind.CONTRACT,
                        SHA,
                        ReleaseGatePolicy.Conclusion.NOT_CHECKED,
                        "SUBSET_EXCEEDED"));
        assertFalse(
                ReleaseGatePolicy.evaluate(
                                SHA,
                                8,
                                8,
                                checks,
                                3,
                                new ReleaseGatePolicy.Waiver("OWNER", SHA, 8, "人工确认"))
                        .allowed());
    }
}

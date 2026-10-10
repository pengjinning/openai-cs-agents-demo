package com.example.airline;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BusinessToolsTest {
    private final BusinessTools tools = new BusinessTools();

    @Test
    void faqSupportsChineseNormalizationAndCompoundQuestions() {
        Conversation conversation = new Conversation("owner", null);
        BusinessTools.Result result = tools.execute(conversation, "faq_lookup_tool",
                Map.of("question", "Wi-Fi、行李和飞机有多少座位？"));
        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(result.userMessage()).contains("Airline-Wifi", "136", "50磅");
        assertThat(AirlineData.seats()).hasSize(136);
        assertThat(AirlineData.seats().stream().filter(seat -> seat.cabin().equals("商务舱"))).hasSize(16);
    }

    @Test
    void missingTripIsNotReplacedByInventedBooking() {
        Conversation conversation = new Conversation("owner", null);
        assertThat(tools.execute(conversation, "get_trip_details", Map.of("message", "你好")).code()).isEqualTo("MISSING_TRIP");
        assertThat(conversation.context.scenario).isNull();
        assertThat(tools.execute(conversation, "get_trip_details", Map.of("message", "巴黎经纽约去奥斯汀")).status()).isEqualTo("SUCCESS");
        assertThat(conversation.context.confirmationNumber).isEqualTo("IR-D204");
    }

    @Test
    void unknownFlightAndNoMatchAreExplicit() {
        Conversation conversation = new Conversation("owner", "disrupted");
        assertThat(tools.execute(conversation, "flight_status_tool", Map.of("flight_number", "UNKNOWN")).status()).isEqualTo("NOT_FOUND");
        assertThat(tools.execute(conversation, "get_matching_flights", Map.of("destination", "Tokyo")).status()).isEqualTo("NOT_FOUND");
        assertThat(tools.execute(conversation, "book_new_flight", Map.of("flight_number", "UNKNOWN")).status()).isEqualTo("REJECTED");
        assertThat(conversation.context.flightNumber).isEqualTo("PA441");
    }

    @Test
    void rebookingRequiresConfirmationReplacesSegmentAndIsIdempotent() {
        Conversation conversation = new Conversation("owner", "disrupted");
        BusinessTools.Result pending = tools.execute(conversation, "book_new_flight", Map.of("flight_number", "NY950"));
        assertThat(pending.status()).isEqualTo("NEEDS_CONFIRMATION");
        assertThat(conversation.snapshot(new AgentCatalog()).pendingOperations().getFirst().booking())
                .containsEntry("flightNumber", "PA441").containsEntry("confirmationNumber", "IR-D204");
        assertThat(conversation.context.flightNumber).isEqualTo("PA441");
        assertThat(tools.execute(conversation, "book_new_flight", Map.of("flight_number", "NY950")).operationId())
                .isEqualTo(pending.operationId());
        BusinessTools.Result completed = tools.confirm(conversation, pending.operationId());
        long revision = conversation.context.seatMapVersion;
        assertThat(tools.confirm(conversation, pending.operationId())).isEqualTo(completed);
        assertThat(conversation.context.seatMapVersion).isEqualTo(revision);
        assertThat(conversation.context.flightNumber).isEqualTo("NY950");
        assertThat(conversation.context.itinerary.stream().map(AirlineData.Flight::number)).containsExactly("PA441", "NY950");
    }

    @Test
    void cancellationPersistsAndBlocksSubsequentSeatChanges() {
        Conversation conversation = new Conversation("owner", "on_time");
        BusinessTools.Result pending = tools.execute(conversation, "cancel_flight", Map.of());
        assertThat(conversation.context.bookingStatus).isEqualTo("CONFIRMED");
        assertThat(tools.confirm(conversation, pending.operationId()).status()).isEqualTo("SUCCESS");
        assertThat(conversation.context.bookingStatus).isEqualTo("CANCELLED");
        assertThat(tools.execute(conversation, "update_seat",
                Map.of("confirmation_number", "LL0EZ6", "new_seat", "15C")).code()).isEqualTo("BOOKING_NOT_ACTIVE");
    }

    @Test
    void validatesOwnershipSeatInventoryAndMapVersion() {
        Conversation conversation = new Conversation("owner", "on_time");
        assertThat(tools.execute(conversation, "update_seat",
                Map.of("confirmation_number", "OTHER", "new_seat", "15C")).code()).isEqualTo("CONFIRMATION_MISMATCH");
        assertThat(tools.execute(conversation, "update_seat",
                Map.of("confirmation_number", "LL0EZ6", "new_seat", "1A")).code()).isEqualTo("SEAT_OCCUPIED");
        assertThat(tools.execute(conversation, "update_seat",
                Map.of("confirmation_number", "LL0EZ6", "new_seat", "99A")).code()).isEqualTo("INVALID_SEAT");
        BusinessTools.Result first = tools.execute(conversation, "update_seat",
                Map.of("confirmation_number", "LL0EZ6", "new_seat", "15C"));
        BusinessTools.Result second = tools.execute(conversation, "update_seat",
                Map.of("confirmation_number", "LL0EZ6", "new_seat", "16C"));
        tools.confirm(conversation, first.operationId());
        assertThat(tools.confirm(conversation, second.operationId()).code()).isEqualTo("STATE_CHANGED");
        assertThat(conversation.context.seatNumber).isEqualTo("15C");
    }

    @Test
    void specialServiceUsesAvailableSeatRatherThanOccupied1A() {
        Conversation conversation = new Conversation("owner", "disrupted");
        BusinessTools.Result operation = tools.execute(conversation, "assign_special_service_seat", Map.of("seat_request", "医疗前排需求"));
        assertThat(tools.confirm(conversation, operation.operationId()).status()).isEqualTo("SUCCESS");
        assertThat(conversation.context.seatNumber).isEqualTo("1B");
        assertThat(conversation.snapshot(new AgentCatalog()).context()).doesNotContainKey("specialServiceNote");
    }

    @Test
    void compensationRequiresPolicyAndPreventsDuplicateIssuance() {
        Conversation conversation = new Conversation("owner", "disrupted");
        assertThat(tools.execute(conversation, "issue_compensation", Map.of("reason", "延误")).code()).isEqualTo("POLICY_REQUIRED");
        tools.execute(conversation, "faq_lookup_tool", Map.of("question", "延误补偿政策"));
        BusinessTools.Result pending = tools.execute(conversation, "issue_compensation", Map.of("reason", "延误"));
        assertThat(tools.confirm(conversation, pending.operationId()).status()).isEqualTo("SUCCESS");
        assertThat(conversation.context.vouchers).hasSize(3);
        String caseId = conversation.context.compensationCaseId;
        assertThat(tools.execute(conversation, "issue_compensation", Map.of("reason", "延误")).code()).isEqualTo("ALREADY_ISSUED");
        assertThat(conversation.context.compensationCaseId).isEqualTo(caseId);
        assertThat(conversation.snapshot(new AgentCatalog()).context()).doesNotContainKeys("compensationCaseId", "itinerary");
    }

    @Test
    void expiredOperationDoesNotWriteState() {
        Conversation conversation = new Conversation("owner", "on_time");
        BusinessTools.Result pending = tools.execute(conversation, "cancel_flight", Map.of());
        Conversation.Operation operation = conversation.pending.get(pending.operationId());
        conversation.pending.put(operation.id(), new Conversation.Operation(operation.id(), operation.tool(),
                operation.arguments(), operation.booking(), Instant.now().minusSeconds(1)));
        assertThat(tools.confirm(conversation, operation.id()).code()).isEqualTo("OPERATION_EXPIRED");
        assertThat(conversation.context.bookingStatus).isEqualTo("CONFIRMED");
    }

    @Test
    void invalidArgumentsAndScenarioChangesAreRejected() {
        assertThatThrownBy(() -> tools.execute(new Conversation("owner", null), "update_seat", Map.of()))
                .isInstanceOf(ApiException.class);
        Conversation conversation = new Conversation("owner", "on_time");
        assertThatThrownBy(() -> conversation.context.load("disrupted")).isInstanceOf(ApiException.class);
    }
}

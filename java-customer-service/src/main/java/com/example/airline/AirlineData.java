package com.example.airline;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class AirlineData {
    private AirlineData() {}

    public record Flight(String number, String origin, String destination, String departure,
                         String arrival, String status, String gate, String seat) {}
    public record Seat(String number, String cabin, boolean exitRow, boolean occupied) {}
    public record SeatMap(long version, String flightNumber, String selectedSeat, List<Seat> seats) {}

    public static final Flight PA441 = new Flight("PA441", "Paris (CDG)", "New York (JFK)",
            "2024-12-09 14:10", "2024-12-09 17:40", "演示：延误5小时，将错过 NY802", "B18", "14C");
    public static final Flight NY802 = new Flight("NY802", "New York (JFK)", "Austin (AUS)",
            "2024-12-09 19:10", "2024-12-09 22:35", "演示：连接已错过", "C7", "14C");
    public static final Flight FLT123 = new Flight("FLT-123", "San Francisco (SFO)", "Los Angeles (LAX)",
            "2024-12-09 16:10", "2024-12-09 17:35", "演示：准点", "A10", "23A");
    public static final List<Flight> ALTERNATIVES = List.of(
            new Flight("NY950", "New York (JFK)", "Austin (AUS)", "2024-12-10 09:45",
                    "2024-12-10 12:30", "演示：可改签", "TBD", "2A"),
            new Flight("NY982", "New York (JFK)", "Austin (AUS)", "2024-12-10 13:20",
                    "2024-12-10 16:05", "演示：可改签", "TBD", "3D"));
    public static final Map<String, Flight> FLIGHTS = Map.of(
            PA441.number(), PA441, NY802.number(), NY802, FLT123.number(), FLT123,
            "NY950", ALTERNATIVES.get(0), "NY982", ALTERNATIVES.get(1));
    public static final Set<String> OCCUPIED = Set.of("1A", "2B", "3C", "5A", "5F", "7B",
            "9A", "10C", "12A", "14B", "16A", "18C", "20A", "22B", "24F");

    public static List<Seat> seats() {
        List<Seat> result = new ArrayList<>();
        for (int row = 1; row <= 24; row++) {
            String letters = row <= 4 ? "ABCD" : "ABCDEF";
            String cabin = row <= 4 ? "商务舱" : row <= 8 ? "优选经济舱" : "经济舱";
            for (char letter : letters.toCharArray()) {
                String number = row + String.valueOf(letter);
                result.add(new Seat(number, cabin, row == 4 || row == 16, OCCUPIED.contains(number)));
            }
        }
        return List.copyOf(result);
    }

    public static final class Context {
        public String scenario;
        public String passengerName;
        public String confirmationNumber;
        public String flightNumber;
        public String seatNumber;
        public String bookingStatus = "UNLOADED";
        public List<Flight> itinerary = List.of();
        public List<String> vouchers = List.of();
        public String compensationCaseId;
        public String specialServiceNote;
        public boolean policyConsulted;
        public long seatMapVersion;

        public void load(String selectedScenario) {
            if (scenario != null) {
                if (!scenario.equals(selectedScenario)) {
                    throw new ApiException(409, "SCENARIO_CONFLICT", "请创建新会话选择另一演示航程");
                }
                return;
            }
            if (!Set.of("on_time", "disrupted").contains(selectedScenario)) {
                throw new ApiException(400, "INVALID_SCENARIO", "无效演示场景");
            }
            scenario = selectedScenario;
            boolean disrupted = selectedScenario.equals("disrupted");
            passengerName = disrupted ? "Morgan Lee" : "Taylor Lee";
            confirmationNumber = disrupted ? "IR-D204" : "LL0EZ6";
            flightNumber = disrupted ? PA441.number() : FLT123.number();
            seatNumber = disrupted ? "14C" : "23A";
            itinerary = disrupted ? List.of(PA441, NY802) : List.of(FLT123);
            bookingStatus = "CONFIRMED";
            seatMapVersion++;
        }
    }
}

package et.elisa.iwf.mapping;

import java.util.Map;
import java.util.Optional;

public final class ErrorMapper {

    private ErrorMapper() {}

    private static final Map<Integer, String> DIAMETER_TO_MAP = Map.of(
        5001, "unknownSubscriber",
        5421, "roamingNotAllowed",
        3002, "systemFailure",
        3004, "systemFailure",
        3005, "systemFailure",
        3006, "systemFailure"
    );

    public static Optional<String> mapError(int diameterResultCode) {
        return Optional.ofNullable(DIAMETER_TO_MAP.get(diameterResultCode));
    }

    public static String describe(int diameterResultCode) {
        return mapError(diameterResultCode).orElse("unknown diameter error " + diameterResultCode);
    }
}

package com.circuitagent.design.parse;

import com.circuitagent.design.model.AttributeValue;
import java.math.BigDecimal;
import java.util.Map;
import java.util.regex.Pattern;

public final class EngineeringUnits {
    private static final Map<String, Integer> POWERS = Map.ofEntries(
        Map.entry("", 0), Map.entry("R", 0), Map.entry("p", -12), Map.entry("n", -9),
        Map.entry("u", -6), Map.entry("µ", -6), Map.entry("μ", -6), Map.entry("m", -3),
        Map.entry("k", 3), Map.entry("K", 3), Map.entry("M", 6), Map.entry("G", 9));
    private static final Pattern NUMBER = Pattern.compile("([+]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?)\\s*([pnuµμmkKMG]?)");
    private static final Pattern RKM = Pattern.compile("([0-9]+)([RkKM])([0-9]+)");
    private EngineeringUnits() {}

    public static AttributeValue normalize(String raw, String unit) {
        String value = raw.trim();
        String suffix = switch (unit) {
            case "Ω" -> "(?i:ohms?|Ω|Ω)";
            case "%" -> "%";
            default -> Pattern.quote(unit);
        };
        value = value.replaceFirst("\\s*" + suffix + "$", "").trim();
        var rkm = RKM.matcher(value);
        BigDecimal number;
        if (unit.equals("Ω") && rkm.matches()) {
            number = new BigDecimal(rkm.group(1) + "." + rkm.group(3)).scaleByPowerOfTen(POWERS.get(rkm.group(2)));
        } else {
            if (unit.equals("Ω") && value.endsWith("R")) value = value.substring(0, value.length() - 1);
            var match = NUMBER.matcher(value);
            if (!match.matches()) throw new IllegalArgumentException("Cannot normalize '" + raw + "' as " + unit);
            number = new BigDecimal(match.group(1)).scaleByPowerOfTen(POWERS.get(match.group(2)));
        }
        return new AttributeValue(raw, number.stripTrailingZeros(), unit);
    }
}

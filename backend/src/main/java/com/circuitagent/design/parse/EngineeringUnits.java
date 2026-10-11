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
    private static final Pattern RKM = Pattern.compile("([0-9]+)([pnuµμmkKMGRVA])([0-9]+)");
    private EngineeringUnits() {}

    public static AttributeValue normalize(String raw, String unit) {
        if (raw.length() > 128) throw new IllegalArgumentException("Engineering value exceeds 128 characters");
        String value = raw.trim();
        if (unit.equals("%") && value.startsWith("±")) value = value.substring(1).trim();
        String suffix = switch (unit) {
            case "Ω" -> "(?i:ohms?|Ω|Ω)";
            case "%" -> "%";
            default -> Pattern.quote(unit);
        };
        value = value.replaceFirst("\\s*" + suffix + "$", "").trim();
        // Observed Sparrow decimal comma has 1–2 fractional digits; reject ambiguous thousands.
        if (value.matches("[+]?[0-9]+,[0-9]{1,2}")) value = value.replace(',', '.');
        var rkm = RKM.matcher(value);
        BigDecimal number;
        if (rkm.matches() && !unit.equals("%")) {
            String marker = rkm.group(2);
            boolean unitMarker = marker.equals(unit) || unit.equals("Ω") && marker.equals("R");
            if (!unitMarker && (!POWERS.containsKey(marker) || marker.equals("R")))
                throw new IllegalArgumentException("Embedded unit marker does not match " + unit);
            number = new BigDecimal(rkm.group(1) + "." + rkm.group(3)).scaleByPowerOfTen(unitMarker ? 0 : POWERS.get(marker));
        } else {
            if (unit.equals("Ω") && value.endsWith("R")) value = value.substring(0, value.length() - 1);
            var match = NUMBER.matcher(value);
            if (!match.matches()) throw new IllegalArgumentException("Cannot normalize '" + raw + "' as " + unit);
            if (unit.equals("%") && !match.group(2).isEmpty()) throw new IllegalArgumentException("Tolerance does not support SI prefixes");
            try {
                number = new BigDecimal(match.group(1)).scaleByPowerOfTen(POWERS.get(match.group(2)));
            } catch (ArithmeticException | NumberFormatException e) { throw new IllegalArgumentException("Engineering value is out of range", e); }
        }
        if (number.precision() > 64 || Math.abs((long) number.scale()) > 64)
            throw new IllegalArgumentException("Engineering value exceeds precision/scale limit 64");
        return new AttributeValue(raw, number.stripTrailingZeros(), unit);
    }
}

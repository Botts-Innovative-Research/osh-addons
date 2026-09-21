package org.sensorhub.impl.sensor.vaisala;

import java.util.regex.Pattern;

public final class VaisalaWeatherData {
    public final long sampleTime = System.currentTimeMillis();
    public double windDirectionMinimum = Double.NaN;
    public double windDirectionAverage = Double.NaN;
    public double windDirectionMaximum = Double.NaN;
    public double windSpeedMinimum = Double.NaN;
    public double windSpeedAverage = Double.NaN;
    public double windSpeedMaximum = Double.NaN;
    public double rainAccumulation = Double.NaN;
    public double rainDuration = Double.NaN;
    public double rainIntensity = Double.NaN;
    public double hailAccumulation = Double.NaN;
    public double hailDuration = Double.NaN;
    public double hailIntensity = Double.NaN;
    public double rainPeakIntensity = Double.NaN;
    public double hailPeakIntensity = Double.NaN;
    public double pressure = Double.NaN;
    public double temperature = Double.NaN;
    public double temperatureInternal = Double.NaN;
    public double relativeHumidity = Double.NaN;
    public double temperatureHeater = Double.NaN;
    public double heatingVoltage = Double.NaN;
    public double supplyVoltage = Double.NaN;
    public double referenceVoltage = Double.NaN;
    public String information = null;

    private static final Pattern NUMBER_WITH_UNIT = Pattern.compile(
            "([+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?)[A-Za-z]?");

    public static VaisalaWeatherData parse(String message) {
        VaisalaWeatherData data = new VaisalaWeatherData();
        String[] fields = message.split(",");
        for (int i = 1; i < fields.length; i++) {
            String field = fields[i].trim();
            int equals = field.indexOf('=');
            if (equals < 0) continue;
            String parameter = field.substring(0, equals);
            String value = field.substring(equals + 1).trim();
            if (parameter.equals("Id")) {
                data.information = value.isEmpty() || value.endsWith("#") ? null : value;
                continue;
            }
            double number = parseNumber(value);
            switch (parameter) {
                case "Dn": data.windDirectionMinimum = number; break;
                case "Dm": data.windDirectionAverage = number; break;
                case "Dx": data.windDirectionMaximum = number; break;
                case "Sn": data.windSpeedMinimum = number; break;
                case "Sm": data.windSpeedAverage = number; break;
                case "Sx": data.windSpeedMaximum = number; break;
                case "Rc": data.rainAccumulation = number; break;
                case "Rd": data.rainDuration = number; break;
                case "Ri": data.rainIntensity = number; break;
                case "Hc": data.hailAccumulation = number; break;
                case "Hd": data.hailDuration = number; break;
                case "Hi": data.hailIntensity = number; break;
                case "Rp": data.rainPeakIntensity = number; break;
                case "Hp": data.hailPeakIntensity = number; break;
                case "Pa": data.pressure = number; break;
                case "Ta": data.temperature = number; break;
                case "Tp": data.temperatureInternal = number; break;
                case "Ua": data.relativeHumidity = number; break;
                case "Th": data.temperatureHeater = number; break;
                case "Vh": data.heatingVoltage = number; break;
                case "Vs": data.supplyVoltage = number; break;
                case "Vr": data.referenceVoltage = number; break;
                default: break;
            }
        }
        return data;
    }

    private static double parseNumber(String value) {
        var match = NUMBER_WITH_UNIT.matcher(value);
        if (!match.matches()) return Double.NaN;
        double number = Double.parseDouble(match.group(1));
        return Double.isFinite(number) ? number : Double.NaN;
    }
}

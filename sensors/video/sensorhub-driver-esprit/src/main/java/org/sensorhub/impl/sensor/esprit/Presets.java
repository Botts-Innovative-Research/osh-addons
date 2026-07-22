package org.sensorhub.impl.sensor.esprit;

public enum Presets {
    WIPERS_START(85), WIPERS_STOP(85), WASH(87), MODE_NIGHT(88), MODE_DAY(89), REBOOT(94);

    private final int presetNum;

    public int getPresetNum() {
        return presetNum;
    }
    private Presets(int presetNum) {
        this.presetNum = presetNum;
    }
}

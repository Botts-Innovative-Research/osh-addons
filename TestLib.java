
import org.sensorhub.impl.sensor.rtlsdr.lib.RtlSdrNative;
public class TestLib {
    public static void main(String[] args) {
        System.out.println("INSTANCE: " + RtlSdrNative.INSTANCE);
        System.out.println("Device count: " + RtlSdrNative.INSTANCE.rtlsdr_get_device_count());
    }
}

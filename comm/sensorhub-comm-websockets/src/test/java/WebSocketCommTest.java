import io.socket.client.IO;
import io.socket.client.Socket;
import org.junit.Test;
import org.sensorhub.api.comm.websockets.WebSocketMessageQueue;
import org.sensorhub.api.comm.websockets.WebSocketMessageQueueConfig;
import org.sensorhub.api.common.SensorHubException;

public class WebSocketCommTest {

    @Test
    public void testConnect() throws InterruptedException {
        var config = new WebSocketMessageQueueConfig();
        config.remoteHost = "localhost";
        config.remotePort = 3000;
        config.enableTLS = false;
//        config.protocol.resourcePath = "/sensorhub/api/datastreams/02fsd4ncqdbg/observations";

        WebSocketMessageQueue provider = new WebSocketMessageQueue();

        try {
            provider.init(config);
            provider.start();
        } catch (SensorHubException e) {
            throw new RuntimeException(e);
        }


        Thread.sleep(60000);
    }
}

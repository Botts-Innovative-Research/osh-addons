/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2021 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.process.video;

import java.awt.Canvas;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Transparency;
import java.awt.color.ColorSpace;
import java.awt.image.BufferStrategy;
import java.awt.image.BufferedImage;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferByte;
import java.awt.image.Raster;
import java.time.Instant;
import javax.swing.JFrame;
import net.opengis.swe.v20.Count;
import net.opengis.swe.v20.DataArray;
import net.opengis.swe.v20.DataType;
import net.opengis.swe.v20.Time;
import org.sensorhub.api.processing.OSHProcessInfo;
import org.vast.data.DataBlockByte;
import org.vast.process.ExecutableProcessImpl;
import org.vast.process.ProcessException;
import org.vast.swe.helper.RasterHelper;


/**
 * <p>
 * Video display window for testing purposes.<br/>
 * It displays decoded RGB video frames or images in a JFrame window.
 * </p>
 *
 * @author Alex Robin
 * @date Jun 1, 2021
 */
public class VideoDisplay extends ExecutableProcessImpl
{
	public static final OSHProcessInfo INFO = new OSHProcessInfo("video:VideoDisplay", "Video Display Window", null, VideoDisplay.class);
	
	protected Time timeStamp;
	protected Count inputWidth;
	protected Count inputHeight;
	protected DataArray imgIn;
	
	protected JFrame window;
	protected BufferStrategy strategy;
	protected Graphics graphicCtx;
	protected BufferedImage bufImg;
	protected String frameSizeTxt;
	
	protected int frameCounter = 0;
	protected long startCountTime;
	protected int fps;
	
	
	public VideoDisplay()
    {
	    this(INFO);
    }
	
	
    public VideoDisplay(OSHProcessInfo procInfo)
    {
    	super(procInfo);
        RasterHelper swe = new RasterHelper();
        
        // inputs    	
        inputData.add("rgbFrame", swe.createRecord()
            .label("Video Frame")
            .addField("time", timeStamp = swe.createTime()
                .asSamplingTimeIsoUTC()
                .label("Frame Timestamp")
                .build())
            .addField("width", inputWidth = swe.createCount()
                .id("IN_WIDTH")
                .label("Input Frame Width")
                .build())
            .addField("height", inputHeight = swe.createCount()
                .id("IN_HEIGHT")
                .label("Input Frame Height")
                .build())
            .addField("img", imgIn = swe.newRgbImage(
                inputWidth,
                inputHeight,
                DataType.BYTE))
            .build());
    }
    
    
    @Override
    public void init() throws ProcessException
    {
        super.init();
        
        // close previous window if needed
        if (window != null) {
            window.dispose();
            window = null;
        }
    }


    /*
     * To init window once we know the frame dimensions
     */
    protected void initJFrame()
    {
        int width = inputWidth.getData().getIntValue();
        int height = inputHeight.getData().getIntValue();        
        
        window = new JFrame("Video Preview");
        var canvas = new Canvas();
        canvas.setPreferredSize(new Dimension(width, height));
        window.add(canvas);
        window.pack();
        window.setVisible(true);
        window.setResizable(false);
        //graphicCtx = canvas.getGraphics();
        canvas.createBufferStrategy(2);
        strategy = canvas.getBufferStrategy();
        
        // create RGB buffered image
        var cs = ColorSpace.getInstance(ColorSpace.CS_sRGB);
        var colorModel = new ComponentColorModel(
            cs, new int[] {8,8,8},
            false, false,
            Transparency.OPAQUE,
            DataBuffer.TYPE_BYTE);
        var raster = Raster.createInterleavedRaster(DataBuffer.TYPE_BYTE,
            width, height,
            width*3, 3,
            new int[] {0, 1, 2}, null);
        bufImg = new BufferedImage(colorModel, raster, false, null);
        
        frameSizeTxt = String.format("%d x %d", width, height);
    }
    

    @Override
    public void execute() throws ProcessException
    {
        // get input encoded frame data
        byte[] frameData = ((DataBlockByte)imgIn.getData()).getUnderlyingObject();
                
        if (window == null)
            initJFrame();
        
        var imgData = ((DataBufferByte)bufImg.getRaster().getDataBuffer()).getData();
        System.arraycopy(frameData, 0, imgData, 0, frameData.length);
        
        // get timestamp
        var ts = timeStamp.getData().getDoubleValue();
        var dateTime = Instant.ofEpochMilli((long)(ts*1000));
        
        var now = System.currentTimeMillis();
        if (now - startCountTime >= 1000)
        {
            fps = (int)Math.round(1000. / (double)(now - startCountTime) * (frameCounter+1));
            startCountTime = now;
            frameCounter = 0;
        }
        else
            frameCounter++;
        
        // draw image
        graphicCtx = strategy.getDrawGraphics();
        graphicCtx.setColor(Color.YELLOW);
        graphicCtx.drawImage(bufImg, 0, 0, null);
        graphicCtx.drawString(frameSizeTxt + ", " + fps + " fps", 10, 20);
        graphicCtx.drawString(dateTime.toString(), 10, 35);        
    }
    
    
    @Override
    public void publishData()
    {
        graphicCtx.dispose();        
        strategy.show();
    }
            
            
    @Override
    public void dispose()
    {
        super.dispose();
        
        if (window != null) {
            window.dispose();
            window = null;
        }
    }
}
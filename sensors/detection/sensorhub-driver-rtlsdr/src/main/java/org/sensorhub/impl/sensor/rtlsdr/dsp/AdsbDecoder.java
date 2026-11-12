/***************************** BEGIN LICENSE BLOCK ***************************
 The contents of this file are subject to the Mozilla Public License, v. 2.0.
 If a copy of the MPL was not distributed with this file, You can obtain one
 at http://mozilla.org/MPL/2.0/.

 Software distributed under the License is distributed on an "AS IS" basis,
 WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 for the specific language governing rights and limitations under the License.

 Copyright (C) 2020-2026 Botts Innovative Research, Inc. All Rights Reserved.
 ******************************* END LICENSE BLOCK ***************************/
package org.sensorhub.impl.sensor.rtlsdr.dsp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;

/**
 * Real-time 1090 MHz Mode S / ADS-B decoder for RTL-SDR raw I/Q baseband streams.
 * Performs pulse detection, 112-bit Extended Squitter PPM demodulation,
 * 24-bit Mode S CRC verification, and CPR latitude/longitude/altitude/speed decoding.
 */
public class AdsbDecoder {
    private static final Logger log = LoggerFactory.getLogger(AdsbDecoder.class);

    public static final int ADSB_SAMPLE_RATE = 2000000; // 2.0 MSPS (0.5 µs per sample)
    private static final int PREAMBLE_US_SAMPLES = 16;  // 8 µs preamble = 16 samples
    private static final int LONG_FRAME_BITS = 112;
    private static final int LONG_FRAME_SAMPLES = LONG_FRAME_BITS * 2; // 224 samples
    private static final int TOTAL_FRAME_SAMPLES = PREAMBLE_US_SAMPLES + LONG_FRAME_SAMPLES; // 240 samples

    public static class AircraftState {
        public String icao;
        public String callsign = "";
        public double lat = Double.NaN;
        public double lon = Double.NaN;
        public double altBaroFt = Double.NaN;
        public double altGeoFt = Double.NaN;
        public double groundSpeed = Double.NaN;
        public double track = Double.NaN;
        public double verticalRate = Double.NaN;
        public String squawk = "";
        public boolean alert = false;
        public boolean emergency = false;
        public boolean isOnGround = false;
        public long lastSeenTime = System.currentTimeMillis();

        // CPR tracking
        int evenCprLat, evenCprLon;
        long evenCprTime;
        int oddCprLat, oddCprLon;
        long oddCprTime;
    }

    public interface AdsbListener {
        void onAircraftUpdate(AircraftState state);
    }

    private final List<AdsbListener> listeners = new CopyOnWriteArrayList<>();
    private final Map<String, AircraftState> aircraftMap = new ConcurrentHashMap<>();

    // Fast lookup table for integer magnitude: sqrt(I^2 + Q^2)
    private static final short[][] MAG_TABLE = new short[256][256];
    private static final char[] AIS_CHARSET = "?ABCDEFGHIJKLMNOPQRSTUVWXYZ????? ???????????????0123456789??????".toCharArray();

    static {
        for (int i = 0; i < 256; i++) {
            float fi = i - 127.5f;
            for (int q = 0; q < 256; q++) {
                float fq = q - 127.5f;
                MAG_TABLE[i][q] = (short) Math.round(Math.sqrt(fi * fi + fq * fq) * 256.0);
            }
        }
    }

    // Rolling sample buffer for continuous streaming across chunk boundaries
    private short[] magBuffer = new short[131072];
    private int magBufferLen = 0;

    // Optional ground station reference coordinates for 1-frame local CPR decoding
    private volatile double receiverLat = Double.NaN;
    private volatile double receiverLon = Double.NaN;

    public void setReceiverLocation(double lat, double lon) {
        this.receiverLat = lat;
        this.receiverLon = lon;
    }

    public void addListener(AdsbListener listener) {
        listeners.add(listener);
    }

    public void removeListener(AdsbListener listener) {
        listeners.remove(listener);
    }

    public Map<String, AircraftState> getAircraftMap() {
        return aircraftMap;
    }

    /**
     * Process interleaved I/Q samples from the RTL-SDR dongle at 2.0 MSPS.
     */
    public synchronized void processSamples(byte[] buffer, int len, int sampleRateHz) {
        int numSamples = len / 2;
        if (numSamples <= 0) return;

        // Ensure rolling buffer has space
        if (magBufferLen + numSamples > magBuffer.length) {
            short[] newBuf = new short[Math.max(magBuffer.length * 2, magBufferLen + numSamples + 4096)];
            System.arraycopy(magBuffer, 0, newBuf, 0, magBufferLen);
            magBuffer = newBuf;
        }

        // Convert I/Q bytes to envelope magnitudes
        for (int i = 0; i < numSamples; i++) {
            int iByte = buffer[i * 2] & 0xFF;
            int qByte = buffer[i * 2 + 1] & 0xFF;
            magBuffer[magBufferLen + i] = MAG_TABLE[iByte][qByte];
        }
        magBufferLen += numSamples;

        // Search for Mode S preambles
        int scanEnd = magBufferLen - TOTAL_FRAME_SAMPLES;
        int i = 0;
        byte[] msg = new byte[14];

        while (i < scanEnd) {
            // Mode S 8 µs preamble: 4 pulses at 0, 2, 7, 9 half-microsecond samples
            int p0 = magBuffer[i];
            int p1 = magBuffer[i + 1];
            int p2 = magBuffer[i + 2];
            int p3 = magBuffer[i + 3];
            int p4 = magBuffer[i + 4];
            int p5 = magBuffer[i + 5];
            int p6 = magBuffer[i + 6];
            int p7 = magBuffer[i + 7];
            int p8 = magBuffer[i + 8];
            int p9 = magBuffer[i + 9];

            // Pulses must be peaks relative to adjacent slots
            if (p0 > p1 && p2 > p3 && p7 > p6 && p9 > p8) {
                int peakSum = p0 + p2 + p7 + p9;
                int highThreshold = peakSum / 6;

                // Valleys must be below threshold, and peak energy must meet minimum noise floor
                if (p4 < highThreshold && p5 < highThreshold && peakSum > 1000) {
                    // Demodulate PPM data bits (112 bits = 224 samples starting at offset 16)
                    int dataOffset = i + PREAMBLE_US_SAMPLES;

                    for (int bit = 0; bit < LONG_FRAME_BITS; bit++) {
                        int s0 = magBuffer[dataOffset + bit * 2];
                        int s1 = magBuffer[dataOffset + bit * 2 + 1];
                        int byteIdx = bit / 8;
                        int bitIdx = 7 - (bit % 8);

                        if (s0 > s1) {
                            msg[byteIdx] |= (1 << bitIdx);
                        } else {
                            msg[byteIdx] &= ~(1 << bitIdx);
                        }
                    }

                    // Check Downlink Format
                    int df = (msg[0] >> 3) & 0x1F;
                    if (df == 17) { // 112-bit Extended Squitter (ADS-B)
                        int crc = computeCrc(msg, 112);
                        if (crc == 0) {
                            decodeExtendedSquitter(msg);
                            i += TOTAL_FRAME_SAMPLES; // Skip entire frame
                            continue;
                        }
                    }
                }
            }
            i++;
        }

        // Shift remaining uninspected samples to start of buffer
        if (i < magBufferLen) {
            int remaining = magBufferLen - i;
            System.arraycopy(magBuffer, i, magBuffer, 0, remaining);
            magBufferLen = remaining;
        } else {
            magBufferLen = 0;
        }
    }

    private static final int[] MODES_CHECKSUM_TABLE = {
        0x3935ea, 0x1c9af5, 0xf1b77e, 0x78dbbf, 0xc397db, 0x9e31e9, 0xb0e2f0, 0x587178,
        0x2c38bc, 0x161c5e, 0x0b0e2f, 0xfa7d13, 0x82c48d, 0xbe9842, 0x5f4c21, 0xd05c14,
        0x682e0a, 0x341705, 0xe5f186, 0x72f8c3, 0xc68665, 0x9cb936, 0x4e5c9b, 0xd8d449,
        0x939020, 0x49c810, 0x24e408, 0x127204, 0x093902, 0x049c81, 0xfdb444, 0x7eda22,
        0x3f6d11, 0xe04c8c, 0x702646, 0x381323, 0xe3f395, 0x8e03ce, 0x4701e7, 0xdc7af7,
        0x91c77f, 0xb719bb, 0xa476d9, 0xadc168, 0x56e0b4, 0x2b705a, 0x15b82d, 0xf52612,
        0x7a9309, 0xc2b380, 0x6159c0, 0x30ace0, 0x185670, 0x0c2b38, 0x06159c, 0x030ace,
        0x018567, 0xff38b7, 0x80665f, 0xbfc92b, 0xa01e91, 0xaff54c, 0x57faa6, 0x2bfd53,
        0xea04ad, 0x8af852, 0x457c29, 0xdd4410, 0x6ea208, 0x375104, 0x1ba882, 0x0dd441,
        0xf91024, 0x7c8812, 0x3e4409, 0xe0d800, 0x706c00, 0x383600, 0x1c1b00, 0x0e0d80,
        0x0706c0, 0x038360, 0x01c1b0, 0x00e0d8, 0x00706c, 0x003836, 0x001c1b, 0xfff409,
        0x000000, 0x000000, 0x000000, 0x000000, 0x000000, 0x000000, 0x000000, 0x000000,
        0x000000, 0x000000, 0x000000, 0x000000, 0x000000, 0x000000, 0x000000, 0x000000,
        0x000000, 0x000000, 0x000000, 0x000000, 0x000000, 0x000000, 0x000000, 0x000000
    };

    /**
     * Compute 24-bit Mode S CRC parity / syndrome.
     * Generator polynomial: 0x1FFF409.
     * Returns 0 if the message passes CRC parity check.
     */
    public static int computeCrc(byte[] msg, int numBits) {
        int crc = 0;
        int offset = (numBits == 112) ? 0 : (112 - 56);
        for (int j = 0; j < numBits - 24; j++) {
            int byteIdx = j / 8;
            int bitIdx = j % 8;
            if ((msg[byteIdx] & (1 << (7 - bitIdx))) != 0) {
                crc ^= MODES_CHECKSUM_TABLE[j + offset];
            }
        }
        int rem = ((msg[(numBits / 8) - 3] & 0xFF) << 16) |
                  ((msg[(numBits / 8) - 2] & 0xFF) << 8)  |
                   (msg[(numBits / 8) - 1] & 0xFF);
        return (crc ^ rem) & 0x00FFFFFF;
    }

    /**
     * Decode DF=17 Extended Squitter payload.
     */
    public void decodeExtendedSquitter(byte[] msg) {
        int icao = ((msg[1] & 0xFF) << 16) | ((msg[2] & 0xFF) << 8) | (msg[3] & 0xFF);
        String icaoHex = String.format("%06X", icao);

        AircraftState state = aircraftMap.computeIfAbsent(icaoHex, k -> {
            AircraftState s = new AircraftState();
            s.icao = k;
            return s;
        });
        state.lastSeenTime = System.currentTimeMillis();

        int tc = (msg[4] >> 3) & 0x1F; // Type Code

        // TC 1-4: Aircraft Identification & Category (Callsign)
        if (tc >= 1 && tc <= 4) {
            long chars6 = (((long) (msg[5] & 0xFF)) << 40) |
                          (((long) (msg[6] & 0xFF)) << 32) |
                          (((long) (msg[7] & 0xFF)) << 24) |
                          (((long) (msg[8] & 0xFF)) << 16) |
                          (((long) (msg[9] & 0xFF)) << 8)  |
                          ((long) (msg[10] & 0xFF));

            StringBuilder sb = new StringBuilder();
            for (int c = 0; c < 8; c++) {
                int ch = (int) ((chars6 >> ((7 - c) * 6)) & 0x3F);
                if (ch < AIS_CHARSET.length) {
                    char l = AIS_CHARSET[ch];
                    if (l != ' ' && l != '?') sb.append(l);
                }
            }
            state.callsign = sb.toString().trim();
            notifyListeners(state);
        }
        // TC 5-8: Surface Position
        else if (tc >= 5 && tc <= 8) {
            state.isOnGround = true;
            boolean odd = (msg[6] & 0x04) != 0;
            int cprLat = (((msg[6] & 0x03) << 15) | ((msg[7] & 0xFF) << 7) | ((msg[8] >> 1) & 0x7F));
            int cprLon = (((msg[8] & 0x01) << 16) | ((msg[9] & 0xFF) << 8) | (msg[10] & 0xFF));

            decodeCprPosition(state, odd, cprLat, cprLon, true);
            notifyListeners(state);
        }
        // TC 9-18: Airborne Position (w/ Barometric Altitude)
        else if (tc >= 9 && tc <= 18) {
            state.isOnGround = false;
            // Barometric Altitude (bits 40..51)
            int rawAlt = ((msg[5] & 0xFF) << 4) | ((msg[6] >> 4) & 0x0F);
            boolean qBit = (rawAlt & 0x10) != 0;
            if (qBit) {
                // 25-foot increments
                int n = ((rawAlt & 0xFE0) >> 1) | (rawAlt & 0x0F);
                state.altBaroFt = n * 25.0 - 1000.0;
            }

            // CPR Format & Coordinates
            boolean odd = (msg[6] & 0x04) != 0;
            int cprLat = (((msg[6] & 0x03) << 15) | ((msg[7] & 0xFF) << 7) | ((msg[8] >> 1) & 0x7F));
            int cprLon = (((msg[8] & 0x01) << 16) | ((msg[9] & 0xFF) << 8) | (msg[10] & 0xFF));

            decodeCprPosition(state, odd, cprLat, cprLon, false);
            notifyListeners(state);
        }
        // TC 19: Airborne Velocity
        else if (tc == 19) {
            int subType = msg[4] & 0x07;
            if (subType == 1 || subType == 2) {
                // Ground Speed
                int ewDir = (msg[5] & 0x04) >> 2;
                int ewVel = ((msg[5] & 0x03) << 8) | (msg[6] & 0xFF);
                int nsDir = (msg[7] & 0x80) >> 7;
                int nsVel = ((msg[7] & 0x7F) << 3) | ((msg[8] >> 5) & 0x07);

                double vEast = (ewDir == 1 ? -1 : 1) * (ewVel - 1);
                double vNorth = (nsDir == 1 ? -1 : 1) * (nsVel - 1);

                state.groundSpeed = Math.round(Math.sqrt(vEast * vEast + vNorth * vNorth));
                double trackRad = Math.atan2(vEast, vNorth);
                double trackDeg = Math.toDegrees(trackRad);
                if (trackDeg < 0) trackDeg += 360.0;
                state.track = Math.round(trackDeg * 10.0) / 10.0;

                // Vertical Rate (bits 68..77)
                int vrSign = (msg[8] & 0x08) >> 3;
                int vrRaw = ((msg[8] & 0x07) << 6) | ((msg[9] >> 2) & 0x3F);
                if (vrRaw > 0) {
                    state.verticalRate = (vrSign == 1 ? -1 : 1) * (vrRaw - 1) * 64;
                }
                notifyListeners(state);
            }
        }
        // TC 20-22: Airborne Position (w/ GNSS/Geometric Altitude)
        else if (tc >= 20 && tc <= 22) {
            state.isOnGround = false;
            int rawAlt = ((msg[5] & 0xFF) << 4) | ((msg[6] >> 4) & 0x0F);
            if (rawAlt > 0) {
                state.altGeoFt = rawAlt * 3.28084; // meters to feet
            }

            boolean odd = (msg[6] & 0x04) != 0;
            int cprLat = (((msg[6] & 0x03) << 15) | ((msg[7] & 0xFF) << 7) | ((msg[8] >> 1) & 0x7F));
            int cprLon = (((msg[8] & 0x01) << 16) | ((msg[9] & 0xFF) << 8) | (msg[10] & 0xFF));

            decodeCprPosition(state, odd, cprLat, cprLon, false);
            notifyListeners(state);
        }
        // TC 28: Emergency Status / Squawk
        else if (tc == 28) {
            int subType = msg[4] & 0x07;
            if (subType == 1) { // Emergency / Priority Status
                int emerg = (msg[5] >> 5) & 0x07;
                state.emergency = (emerg != 0);
                int sqRaw = (((msg[5] & 0x1F) << 8) | (msg[6] & 0xFF));
                // 13-bit Gillham squawk code
                state.squawk = String.format("%04o", sqRaw);
                notifyListeners(state);
            }
        }
    }

    /**
     * Compact Position Reporting (CPR) decoding (ICAO Annex 10 / RTCA DO-260B).
     * Uses relative (local) CPR decoding first if a recent prior position or receiver position
     * is available, otherwise falls back to global airborne CPR decoding across even/odd frame pairs.
     */
    private void decodeCprPosition(AircraftState state, boolean odd, int cprLat, int cprLon, boolean surface) {
        long now = System.currentTimeMillis();
        if (odd) {
            state.oddCprLat = cprLat;
            state.oddCprLon = cprLon;
            state.oddCprTime = now;
        } else {
            state.evenCprLat = cprLat;
            state.evenCprLon = cprLon;
            state.evenCprTime = now;
        }

        // 1. Try Relative (Local) CPR Decoding if we have a recent reference position
        // Reference candidate A: Aircraft's own previous valid coordinates (valid if seen within 60 seconds)
        double refLat = Double.NaN;
        double refLon = Double.NaN;
        if (!Double.isNaN(state.lat) && !Double.isNaN(state.lon) && (now - state.lastSeenTime) < 60000) {
            refLat = state.lat;
            refLon = state.lon;
        } else if (!Double.isNaN(receiverLat) && !Double.isNaN(receiverLon)) {
            // Reference candidate B: Configured ground receiver location
            refLat = receiverLat;
            refLon = receiverLon;
        }

        if (!Double.isNaN(refLat) && !Double.isNaN(refLon)) {
            double[] relPos = decodeCprRelative(refLat, refLon, cprLat, cprLon, odd, surface);
            if (relPos != null) {
                state.lat = Math.round(relPos[0] * 100000.0) / 100000.0;
                state.lon = Math.round(relPos[1] * 100000.0) / 100000.0;
                return;
            }
        }

        // 2. Global CPR Decoding: requires both even and odd messages within 10 seconds
        if (surface) {
            return; // Surface position requires relative decoding with reference location
        }

        if (Math.abs(state.evenCprTime - state.oddCprTime) > 10000 || state.evenCprTime == 0 || state.oddCprTime == 0) {
            return;
        }

        double dLat0 = 360.0 / 60.0;
        double dLat1 = 360.0 / 59.0;

        int j = (int) Math.floor(((59.0 * state.evenCprLat - 60.0 * state.oddCprLat) / 131072.0) + 0.5);
        double rLat0 = dLat0 * (posMod(j, 60) + (double) state.evenCprLat / 131072.0);
        double rLat1 = dLat1 * (posMod(j, 59) + (double) state.oddCprLat / 131072.0);

        if (rLat0 >= 270.0) rLat0 -= 360.0;
        if (rLat1 >= 270.0) rLat1 -= 360.0;

        int nl0 = cprNl(rLat0);
        int nl1 = cprNl(rLat1);

        if (nl0 != nl1) return; // Aircraft crossed latitude band

        double finalLat, finalLon;
        if (state.evenCprTime > state.oddCprTime) {
            // Even frame is newest
            finalLat = rLat0;
            int nl = nl0;
            double dLon = 360.0 / Math.max(1, nl);
            int m = (int) Math.floor(((state.evenCprLon * (nl - 1) - state.oddCprLon * nl) / 131072.0) + 0.5);
            finalLon = dLon * (posMod(m, nl) + (double) state.evenCprLon / 131072.0);
        } else {
            // Odd frame is newest
            finalLat = rLat1;
            int nl = nl1;
            double dLon = 360.0 / Math.max(1, nl - 1);
            int m = (int) Math.floor(((state.evenCprLon * (nl - 1) - state.oddCprLon * nl) / 131072.0) + 0.5);
            finalLon = dLon * (posMod(m, nl - 1) + (double) state.oddCprLon / 131072.0);
        }

        if (finalLon >= 180.0) finalLon -= 360.0;

        if (finalLat >= -90.0 && finalLat <= 90.0 && finalLon >= -180.0 && finalLon <= 180.0) {
            state.lat = Math.round(finalLat * 100000.0) / 100000.0;
            state.lon = Math.round(finalLon * 100000.0) / 100000.0;
        }
    }

    /**
     * Relative (Local) CPR Decoding per RTCA DO-260B / 1090-WP29-07.
     * Reconstructs exact latitude/longitude from a single CPR frame given a known reference position
     * within 1/2 cell (~180 NM airborne, ~45 NM surface).
     *
     * @return double[2] { lat, lon } or null if invalid/out-of-cell
     */
    public static double[] decodeCprRelative(double reflat, double reflon, int cprLat, int cprLon, boolean odd, boolean surface) {
        double airDlat = (surface ? 90.0 : 360.0) / (odd ? 59.0 : 60.0);
        double fractionalLat = cprLat / 131072.0;
        double fractionalLon = cprLon / 131072.0;

        // Latitude index j
        int j = (int) (Math.floor(reflat / airDlat) +
                Math.floor(0.5 + modDouble(reflat, airDlat) / airDlat - fractionalLat));
        double rLat = airDlat * (j + fractionalLat);
        if (rLat >= 270.0) rLat -= 360.0;

        if (rLat < -90.0 || rLat > 90.0) {
            return null;
        }
        if (Math.abs(rLat - reflat) > (airDlat / 2.0)) {
            return null; // Exceeds 1/2 cell ambiguity boundary
        }

        // Longitude index m
        int nl = cprNl(rLat);
        int ni = nl - (odd ? 1 : 0);
        if (ni < 1) ni = 1;
        double airDlon = (surface ? 90.0 : 360.0) / ni;

        int m = (int) (Math.floor(reflon / airDlon) +
                Math.floor(0.5 + modDouble(reflon, airDlon) / airDlon - fractionalLon));
        double rLon = airDlon * (m + fractionalLon);
        if (rLon > 180.0) rLon -= 360.0;
        if (rLon < -180.0) rLon += 360.0;

        if (Math.abs(rLon - reflon) > (airDlon / 2.0)) {
            return null;
        }

        return new double[]{rLat, rLon};
    }

    private static double modDouble(double a, double b) {
        double res = a % b;
        if (res < 0) res += b;
        return res;
    }

    private static int cprNl(double lat) {
        if (Math.abs(lat) >= 87.0) return 1;
        double a = 1.0 - Math.cos(Math.PI / 30.0);
        double b = Math.cos(Math.PI * lat / 180.0);
        double c = 1.0 - (a / (b * b));
        if (c < -1.0) c = -1.0;
        if (c > 1.0) c = 1.0;
        return (int) Math.floor(2.0 * Math.PI / Math.acos(c));
    }

    private static int posMod(int x, int y) {
        int m = x % y;
        return (m < 0) ? m + y : m;
    }

    private void notifyListeners(AircraftState state) {
        for (AdsbListener l : listeners) {
            try {
                l.onAircraftUpdate(state);
            } catch (Exception e) {
                log.trace("Error dispatching ADS-B update: {}", e.getMessage());
            }
        }
    }
}

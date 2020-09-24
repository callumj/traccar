/*
 * Copyright 2018 Anton Tananaev (anton@traccar.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar.protocol;

import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.*;
import org.traccar.database.IdentityManager;
import org.traccar.helper.Checksum;
import org.traccar.helper.DateBuilder;
import org.traccar.helper.UnitsConverter;
import org.traccar.model.Position;

import java.net.SocketAddress;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;

public class FreematicsProtocolDecoder extends BaseProtocolDecoder {

    private static final Logger LOGGER = LoggerFactory.getLogger(FreematicsProtocolDecoder.class);
    private final IdentityManager identityManager = Context.getIdentityManager();

    public FreematicsProtocolDecoder(Protocol protocol) {
        super(protocol);
    }

    private Object decodeEvent(
            Channel channel, SocketAddress remoteAddress, String sentence) {

        DeviceSession deviceSession = null;
        String event = null;
        String time = null;

        for (String pair : sentence.split(",")) {
            String[] data = pair.split("=");
            String key = data[0];
            String value = data[1];
            switch (key) {
                case "ID":
                case "VIN":
                    if (deviceSession == null) {
                        deviceSession = getDeviceSession(channel, remoteAddress, value);
                    }
                    break;
                case "EV":
                    event = value;
                    break;
                case "TS":
                    time = value;
                    break;
                default:
                    break;
            }
        }

        if (channel != null && deviceSession != null && event != null && time != null) {
            String message = String.format("1#EV=%s,RX=1,TS=%s", event, time);
            message += '*' + Checksum.sum(message);
            channel.writeAndFlush(new NetworkMessage(message, remoteAddress));
        }

        return null;
    }

    private Object decodePosition(
            Channel channel, SocketAddress remoteAddress, String sentence) throws Exception {

        DeviceSession deviceSession = getDeviceSession(channel, remoteAddress);
        if (deviceSession == null) {
            return null;
        }

        List<Position> positions = new LinkedList<>();
        Position position = null;
        DateBuilder dateBuilder = null;

        Position last = identityManager.getLastPosition(deviceSession.getDeviceId());

        for (String pair : sentence.split(",")) {
            String[] data = pair.split("[=:]");
            int key = Integer.parseInt(data[0], 16);
            String value = data[1];
            String hexString = Integer.toHexString(key);

            LOGGER.info("key={} int={} hex={} value={}", data[0], key, hexString, value);
            switch (key) {
                case 0x0:
                    if (position != null) {
                        position.setTime(dateBuilder.getDate());
                        positions.add(position);
                    }
                    position = new Position(getProtocolName());
                    position.setDeviceId(deviceSession.getDeviceId());
                    position.setValid(true);
                    dateBuilder = new DateBuilder(new Date());
                    break;
                case 0x11:
                    value = ("000000" + value).substring(value.length());
                    dateBuilder.setDateReverse(
                            Integer.parseInt(value.substring(0, 2)),
                            Integer.parseInt(value.substring(2, 4)),
                            Integer.parseInt(value.substring(4)));
                    break;
                case 0x10:
                    value = ("00000000" + value).substring(value.length());
                    dateBuilder.setTime(
                            Integer.parseInt(value.substring(0, 2)),
                            Integer.parseInt(value.substring(2, 4)),
                            Integer.parseInt(value.substring(4, 6)),
                            Integer.parseInt(value.substring(6)) * 10);
                    break;
                case 0xA:
                    double lat = Double.parseDouble(value);
                    if (lat == 0 && last != null && last.getLatitude() != 0)
                        lat = last.getLatitude();
                    position.setLatitude(lat);
                    break;
                case 0xB:
                    double lng = Double.parseDouble(value);
                    if (lng == 0 && last != null && last.getLongitude() != 0)
                        lng = last.getLongitude();
                    position.setLongitude(lng);
                    break;
                case 0xC:
                    double alt = Double.parseDouble(value);
                    if (alt == 0 && last != null && last.getAltitude() != 0)
                        alt = last.getAltitude();
                    position.setAltitude(alt);
                    break;
                case 0xD:
                    double speed = UnitsConverter.knotsFromKph(Double.parseDouble(value));
                    if (speed < 2.0) {
                        LOGGER.info("Zeroing out speed");
                        speed = 0.0;
                    }
                    position.setSpeed(speed);
                    break;
                case 0xE:
                    double course = Integer.parseInt(value);
                    if (course == 0 && last != null && last.getCourse() != 0)
                        course = last.getCourse();
                    position.setCourse(course);
                    break;
                case 0xF:
                    position.set(Position.KEY_SATELLITES, Double.parseDouble(value));
                    break;
                case 0x12:
                    position.set(Position.KEY_HDOP, Double.parseDouble(value));
                    break;
                case 0x20:
                    position.set(Position.KEY_ACCELERATION, value);
                    break;
                case 0x24:
                    position.set(Position.KEY_BATTERY, Double.parseDouble(value));
                    break;
                case 0x81:
                    position.set(Position.KEY_RSSI, Double.parseDouble(value));
                    break;
                case 0x82:
                    position.set(Position.KEY_DEVICE_TEMP, Double.parseDouble(value));
                    break;
                case 0x104:
                    position.set(Position.KEY_ENGINE_LOAD, Double.parseDouble(value));
                    break;
                case 0x105:
                    position.set(Position.KEY_COOLANT_TEMP, Double.parseDouble(value));
                    break;
                case 0x10c:
                    position.set(Position.KEY_RPM, Double.parseDouble(value));
                    break;
                case 0x10d:
                    position.set(Position.KEY_OBD_SPEED, UnitsConverter.knotsFromKph(Integer.parseInt(value)));
                    break;
                case 0x111:
                    position.set(Position.KEY_THROTTLE, Double.parseDouble(value));
                    break;
                case 0x12F:
                    int fuelLevel = Integer.parseInt(value);
                    LOGGER.info("Fuel: {} {}", fuelLevel * 1.0, value);
                    if (fuelLevel != 0) {
                        position.set(Position.KEY_FUEL_LEVEL, fuelLevel * 1.0);
                    }
                    break;
                default:
                    if (!value.equalsIgnoreCase("0"))
                        position.set(Position.PREFIX_IO + key + "." + hexString, value);
                    break;
            }
        }

        if (position != null) {
            position.setTime(dateBuilder.getDate());
            positions.add(position);

            LOGGER.info("position={}", position.getAttributes().entrySet());
        }

        return positions;
    }

    @Override
    protected Object decode(
            Channel channel, SocketAddress remoteAddress, Object msg) throws Exception {

        String sentence = (String) msg;
        int startIndex = sentence.indexOf('#');
        int endIndex = sentence.indexOf('*');

        if (startIndex > 0 && endIndex > 0) {
            sentence = sentence.substring(startIndex + 1, endIndex);

            if (sentence.startsWith("EV")) {
                return decodeEvent(channel, remoteAddress, sentence);
            } else {
                return decodePosition(channel, remoteAddress, sentence);
            }
        }

        return null;
    }

}

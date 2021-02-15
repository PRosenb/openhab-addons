/**
 * Copyright (c) 2010-2021 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.bluetooth.generic.internal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.bluetooth.BluetoothBindingConstants;
import org.openhab.binding.bluetooth.BluetoothCharacteristic;
import org.openhab.binding.bluetooth.BluetoothCompletionStatus;
import org.openhab.binding.bluetooth.BluetoothDevice.ConnectionState;
import org.openhab.binding.bluetooth.ConnectedBluetoothHandler;
import org.openhab.core.library.types.StringType;
import org.openhab.core.thing.Channel;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.builder.ChannelBuilder;
import org.openhab.core.thing.binding.builder.ThingBuilder;
import org.openhab.core.thing.type.ChannelTypeUID;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.openhab.core.types.State;
import org.openhab.core.util.HexUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sputnikdev.bluetooth.gattparser.BluetoothGattParser;
import org.sputnikdev.bluetooth.gattparser.BluetoothGattParserFactory;
import org.sputnikdev.bluetooth.gattparser.FieldHolder;
import org.sputnikdev.bluetooth.gattparser.GattRequest;
import org.sputnikdev.bluetooth.gattparser.GattResponse;
import org.sputnikdev.bluetooth.gattparser.spec.Characteristic;
import org.sputnikdev.bluetooth.gattparser.spec.Field;

/**
 * This is a handler for generic connected bluetooth devices that dynamically generates
 * channels based off of a bluetooth device's GATT characteristics.
 *
 * @author Connor Petty - Initial contribution
 * @author Peter Rosenberg - Use notifications
 *
 */
@NonNullByDefault
public class GenericBluetoothHandler extends ConnectedBluetoothHandler {

    private final Logger logger = LoggerFactory.getLogger(GenericBluetoothHandler.class);
    private final Map<BluetoothCharacteristic, CharacteristicHandler> charHandlers = new ConcurrentHashMap<>();
    private final Map<ChannelUID, CharacteristicHandler> channelHandlers = new ConcurrentHashMap<>();
    private final BluetoothGattParser gattParser = BluetoothGattParserFactory.getDefault();
    private final CharacteristicChannelTypeProvider channelTypeProvider;
    private final Map<CharacteristicHandler, List<ChannelUID>> handlerToChannels = new ConcurrentHashMap<>();

    private @Nullable ScheduledFuture<?> readCharacteristicJob = null;

    public GenericBluetoothHandler(Thing thing, CharacteristicChannelTypeProvider channelTypeProvider) {
        super(thing);
        this.channelTypeProvider = channelTypeProvider;
    }

    @Override
    public void initialize() {
        super.initialize();

        GenericBindingConfiguration config = getConfigAs(GenericBindingConfiguration.class);
        readCharacteristicJob = scheduler.scheduleWithFixedDelay(() -> {
            if (device.getConnectionState() == ConnectionState.CONNECTED) {
                if (resolved) {
                    handlerToChannels.forEach((charHandler, channelUids) -> {
                        // Only read the value manually if notification is not on.
                        // Also read it the first time before we activate notifications below.
                        if (!device.isNotifying(charHandler.characteristic) && charHandler.canRead()) {
                            device.readCharacteristic(charHandler.characteristic);
                            try {
                                // TODO the ideal solution would be to use locks/conditions and timeouts
                                // Kbetween this code and `onCharacteristicReadComplete` but
                                // that would overcomplicate the code a bit and I plan
                                // on implementing a better more generalized solution later
                                Thread.sleep(50);
                            } catch (InterruptedException e) {
                                return;
                            }
                        }
                        if (charHandler.characteristic.canNotify()) {
                            ChannelUID channelUID = charHandler.getChannelUID(null);
                            // Enabled/Disable notifications dependent on if the channel is linked.
                            if (isLinked(channelUID)) {
                                if (!device.isNotifying(charHandler.characteristic)) {
                                    device.enableNotifications(charHandler.characteristic);
                                }
                            } else {
                                if (device.isNotifying(charHandler.characteristic)) {
                                    device.disableNotifications(charHandler.characteristic);
                                }
                            }
                        }
                    });
                } else {
                    // if we are connected and still haven't been able to resolve the services, try disconnecting and
                    // then connecting again
                    device.disconnect();
                }
            }
        }, 15, config.pollingInterval, TimeUnit.SECONDS);
    }

    @Override
    public void dispose() {
        ScheduledFuture<?> future = readCharacteristicJob;
        if (future != null) {
            future.cancel(true);
        }
        super.dispose();

        charHandlers.clear();
        channelHandlers.clear();
        handlerToChannels.clear();
    }

    @Override
    public void onServicesDiscovered() {
        if (!resolved) {
            resolved = true;
            logger.trace("Service discovery completed for '{}'", address);
            updateThingChannels();
        }
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        super.handleCommand(channelUID, command);

        CharacteristicHandler handler = channelHandlers.get(channelUID);
        if (handler != null) {
            handler.handleCommand(channelUID, command);
        }
    }

    @Override
    public void onCharacteristicReadComplete(BluetoothCharacteristic characteristic, BluetoothCompletionStatus status) {
        super.onCharacteristicReadComplete(characteristic, status);
        if (status == BluetoothCompletionStatus.SUCCESS) {
            byte[] data = characteristic.getByteValue();
            getCharacteristicHandler(characteristic).handleCharacteristicUpdate(data);
        }
    }

    @Override
    public void onCharacteristicUpdate(BluetoothCharacteristic characteristic) {
        super.onCharacteristicUpdate(characteristic);
        byte[] data = characteristic.getByteValue();
        getCharacteristicHandler(characteristic).handleCharacteristicUpdate(data);
    }

    private void updateThingChannels() {
        List<Channel> channels = device.getServices().stream()//
                .flatMap(service -> service.getCharacteristics().stream())//
                .flatMap(characteristic -> {
                    logger.trace("{} processing characteristic {}", address, characteristic.getUuid());
                    CharacteristicHandler handler = getCharacteristicHandler(characteristic);
                    List<Channel> chans = handler.buildChannels();
                    List<ChannelUID> chanUids = chans.stream().map(Channel::getUID).collect(Collectors.toList());
                    for (Channel channel : chans) {
                        channelHandlers.put(channel.getUID(), handler);
                    }
                    handlerToChannels.put(handler, chanUids);
                    return chans.stream();
                })//
                .collect(Collectors.toList());

        ThingBuilder builder = editThing();
        boolean changed = false;
        for (Channel channel : channels) {
            logger.trace("{} attempting to add channel {}", address, channel.getLabel());
            // we only want to add each channel, not replace all of them
            if (getThing().getChannel(channel.getUID()) == null) {
                changed = true;
                builder.withChannel(channel);
            }
        }
        if (changed) {
            updateThing(builder.build());
        }
    }

    private CharacteristicHandler getCharacteristicHandler(BluetoothCharacteristic characteristic) {
        return Objects.requireNonNull(charHandlers.computeIfAbsent(characteristic, CharacteristicHandler::new));
    }

    private boolean readCharacteristic(BluetoothCharacteristic characteristic) {
        return device.readCharacteristic(characteristic);
    }

    private boolean writeCharacteristic(BluetoothCharacteristic characteristic, byte[] data) {
        characteristic.setValue(data);
        return device.writeCharacteristic(characteristic);
    }

    private class CharacteristicHandler {

        private BluetoothCharacteristic characteristic;

        public CharacteristicHandler(BluetoothCharacteristic characteristic) {
            this.characteristic = characteristic;
        }

        private String getCharacteristicUUID() {
            return characteristic.getUuid().toString();
        }

        public void handleCommand(ChannelUID channelUID, Command command) {

            // Handle REFRESH
            if (command == RefreshType.REFRESH) {
                if (canRead()) {
                    readCharacteristic(characteristic);
                }
                return;
            }

            // handle write
            if (command instanceof State) {
                State state = (State) command;
                String characteristicUUID = getCharacteristicUUID();
                try {
                    if (gattParser.isKnownCharacteristic(characteristicUUID)) {
                        String fieldName = getFieldName(channelUID);
                        if (fieldName != null) {
                            updateCharacteristic(fieldName, state);
                        } else {
                            logger.warn("Characteristic has no field name!");
                        }
                    } else if (state instanceof StringType) {
                        // unknown characteristic
                        byte[] data = HexUtils.hexToBytes(state.toString());
                        if (!writeCharacteristic(characteristic, data)) {
                            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                                    "Could not write data to characteristic: " + characteristicUUID);
                        }
                    }
                } catch (RuntimeException ex) {
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                            "Could not update bluetooth device. Error: " + ex.getMessage());
                }
            }
        }

        private void updateCharacteristic(String fieldName, State state) {
            // TODO maybe we should check if the characteristic is authenticated?
            String characteristicUUID = getCharacteristicUUID();

            if (gattParser.isValidForWrite(characteristicUUID)) {
                GattRequest request = gattParser.prepare(characteristicUUID);
                try {
                    BluetoothChannelUtils.updateHolder(gattParser, request, fieldName, state);
                    byte[] data = gattParser.serialize(request);

                    if (!writeCharacteristic(characteristic, data)) {
                        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                                "Could not write data to characteristic: " + characteristicUUID);
                    }
                } catch (NumberFormatException ex) {
                    logger.warn("Could not parse characteristic value: {} : {}", characteristicUUID, state, ex);
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                            "Could not parse characteristic value: " + characteristicUUID + " : " + state);
                }
            }
        }

        public void handleCharacteristicUpdate(byte[] data) {
            String characteristicUUID = getCharacteristicUUID();
            if (gattParser.isKnownCharacteristic(characteristicUUID)) {
                GattResponse response = gattParser.parse(characteristicUUID, data);
                for (FieldHolder holder : response.getFieldHolders()) {
                    Field field = holder.getField();
                    ChannelUID channelUID = getChannelUID(field);
                    updateState(channelUID, BluetoothChannelUtils.convert(gattParser, holder));
                }
            } else {
                // this is a raw channel
                String hex = HexUtils.bytesToHex(data);
                ChannelUID channelUID = getChannelUID(null);
                updateState(channelUID, new StringType(hex));
            }
        }

        public List<Channel> buildChannels() {
            List<Channel> channels = new ArrayList<>();
            String charUUID = getCharacteristicUUID();
            Characteristic gattChar = gattParser.getCharacteristic(charUUID);
            if (gattChar != null) {
                List<Field> fields = gattParser.getFields(charUUID);

                String label = null;
                // check if the characteristic has only on field, if so use its name as label
                if (fields.size() == 1) {
                    label = gattChar.getName();
                }

                Map<String, List<Field>> fieldsMapping = fields.stream().collect(Collectors.groupingBy(Field::getName));

                for (List<Field> fieldList : fieldsMapping.values()) {
                    Field field = fieldList.get(0);
                    if (fieldList.size() > 1) {
                        if (field.isFlagField() || field.isOpCodesField()) {
                            logger.debug("Skipping flags/op codes field: {}.", charUUID);
                        } else {
                            logger.warn("Multiple fields with the same name found: {} / {}. Skipping these fields.",
                                    charUUID, field.getName());
                        }
                        continue;
                    }

                    if (isFieldSupported(field)) {
                        Channel channel = buildFieldChannel(field, label, !gattChar.isValidForWrite());
                        if (channel != null) {
                            channels.add(channel);
                        } else {
                            logger.warn("Unable to build channel for field: {}", field.getName());
                        }
                    } else {
                        logger.warn("GATT field is not supported: {} / {} / {}", charUUID, field.getName(),
                                field.getFormat());
                    }
                }
            } else {
                channels.add(buildUnknownChannel());
            }
            return channels;
        }

        private Channel buildUnknownChannel() {
            ChannelUID channelUID = getChannelUID(null);
            ChannelTypeUID channelTypeUID = new ChannelTypeUID(BluetoothBindingConstants.BINDING_ID, "char-unknown");
            return ChannelBuilder.create(channelUID).withType(channelTypeUID).withProperties(getChannelProperties(null))
                    .build();
        }

        public boolean canRead() {
            String charUUID = getCharacteristicUUID();
            if (gattParser.isKnownCharacteristic(charUUID)) {
                return gattParser.isValidForRead(charUUID);
            }
            return characteristic.canRead();
        }

        public boolean canWrite() {
            String charUUID = getCharacteristicUUID();
            if (gattParser.isKnownCharacteristic(charUUID)) {
                return gattParser.isValidForWrite(charUUID);
            }
            return characteristic.canWrite();
        }

        private boolean isAdvanced() {
            return !gattParser.isKnownCharacteristic(getCharacteristicUUID());
        }

        private boolean isFieldSupported(Field field) {
            return field.getFormat() != null;
        }

        private @Nullable Channel buildFieldChannel(Field field, @Nullable String charLabel, boolean readOnly) {
            String label = charLabel != null ? charLabel : field.getName();
            String acceptedType = BluetoothChannelUtils.getItemType(field);
            if (acceptedType == null) {
                // unknown field format
                return null;
            }

            ChannelUID channelUID = getChannelUID(field);

            logger.debug("Building a new channel for a field: {}", channelUID.getId());

            ChannelTypeUID channelTypeUID = channelTypeProvider.registerChannelType(getCharacteristicUUID(),
                    isAdvanced(), readOnly, field);

            return ChannelBuilder.create(channelUID, acceptedType).withType(channelTypeUID)
                    .withProperties(getChannelProperties(field.getName())).withLabel(label).build();
        }

        private ChannelUID getChannelUID(@Nullable Field field) {
            StringBuilder builder = new StringBuilder();
            builder.append("service-")//
                    .append(toBluetoothHandle(characteristic.getService().getUuid()))//
                    .append("-char-")//
                    .append(toBluetoothHandle(characteristic.getUuid()));
            if (field != null) {
                builder.append("-").append(BluetoothChannelUtils.encodeFieldName(field.getName()));
            }
            return new ChannelUID(getThing().getUID(), builder.toString());
        }

        private String toBluetoothHandle(UUID uuid) {
            long leastSig = uuid.getLeastSignificantBits();
            long mostSig = uuid.getMostSignificantBits();

            if (leastSig == BluetoothBindingConstants.BLUETOOTH_BASE_UUID) {
                return "0x" + Long.toHexString(mostSig >> 32).toUpperCase();
            }
            return uuid.toString().toUpperCase();
        }

        private @Nullable String getFieldName(ChannelUID channelUID) {
            String channelId = channelUID.getId();
            int index = channelId.lastIndexOf("-");
            if (index == -1) {
                throw new IllegalArgumentException(
                        "ChannelUID '" + channelUID + "' is not a valid GATT channel format");
            }
            String encodedFieldName = channelId.substring(index + 1);
            if (encodedFieldName.isEmpty()) {
                return null;
            }
            return BluetoothChannelUtils.decodeFieldName(encodedFieldName);
        }

        private Map<String, String> getChannelProperties(@Nullable String fieldName) {
            Map<String, String> properties = new HashMap<>();
            if (fieldName != null) {
                properties.put(GenericBindingConstants.PROPERTY_FIELD_NAME, fieldName);
            }
            properties.put(GenericBindingConstants.PROPERTY_SERVICE_UUID,
                    characteristic.getService().getUuid().toString());
            properties.put(GenericBindingConstants.PROPERTY_CHARACTERISTIC_UUID, getCharacteristicUUID());
            return properties;
        }
    }
}

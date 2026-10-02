package com.ccvirtualcoms.synaxis;

import com.ccvirtualcoms.content.VirtualComsBlockEntity;
import com.verr1.synaxis.foundation.cimulink.core.component.ComponentSchema;
import com.verr1.synaxis.foundation.cimulink.core.component.ComponentTypeId;
import com.verr1.synaxis.foundation.cimulink.core.component.ExecutionDomain;
import com.verr1.synaxis.foundation.cimulink.core.signal.SignalValue;
import com.verr1.synaxis.foundation.cimulink.game.body.GameThreadPlantPort;
import com.verr1.synaxis.foundation.cimulink.game.endpoint.EndpointAddress;
import com.verr1.synaxis.foundation.cimulink.game.endpoint.EndpointId;

/**
 * Exposes a placed virtual serial port board to Synaxis as a "plant port" - the bridge object that
 * lets a Cimulink circuit reach a block entity that lives in the world.
 *
 * <p>Registered through {@code PlantPortProviders} during common setup.
 */
public final class VirtualComsPlantPort implements GameThreadPlantPort {

    private final VirtualComsBlockEntity board;
    private final EndpointId endpointId;
    private final String deviceName;

    public VirtualComsPlantPort(VirtualComsBlockEntity board, EndpointId endpointId, String deviceName) {
        this.board = board;
        this.endpointId = endpointId;
        this.deviceName = deviceName;
    }

    @Override
    public EndpointId endpointId() {
        return this.endpointId;
    }

    @Override
    public EndpointAddress address() {
        return this.board.address();
    }

    @Override
    public String deviceName() {
        return this.deviceName;
    }

    @Override
    public ComponentTypeId componentType() {
        return VirtualComsComponent.ID;
    }

    @Override
    public ComponentSchema schema() {
        return this.board.schema();
    }

    @Override
    public boolean supportsDomain(ExecutionDomain domain) {
        return domain == ExecutionDomain.GAME_TICK;
    }

    /** The graph wrote into one of our input terminals. */
    @Override
    public void applyInput(String port, SignalValue value) {
        this.board.writeFromSynaxis(port, value);
    }

    /** The graph wants to read one of our output terminals. */
    @Override
    public SignalValue readOutput(String port) {
        return this.board.readForSynaxis(port);
    }
}

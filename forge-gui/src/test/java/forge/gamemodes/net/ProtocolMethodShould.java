package forge.gamemodes.net;

import forge.gamemodes.net.client.IToServer;
import forge.gamemodes.net.client.NetGameController;
import forge.gamemodes.net.event.GuiGameEvent;
import forge.gamemodes.net.event.IdentifiableNetEvent;
import forge.gamemodes.net.event.NetEvent;
import forge.interfaces.IGameController;
import forge.interfaces.IMacroSystem;
import org.testng.annotations.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Pins the reflection contract used by the network protocol dispatcher. */
public class ProtocolMethodShould {

    @Test
    public void resolveEveryMethodOnItsDeclaredTargetInterface() throws Exception {
        for (final ProtocolMethod protocolMethod : ProtocolMethod.values()) {
            final Method expected = protocolMethod.getTargetInterface()
                    .getMethod(protocolMethod.name(), protocolMethod.getArgTypes());

            assertThat(protocolMethod.getMethod())
                    .as("%s on %s", protocolMethod, protocolMethod.getTargetInterface().getSimpleName())
                    .isEqualTo(expected);
        }
    }

    @Test
    public void delegateMacroProtocolMethodsToTheControllerMacroSystem() throws Exception {
        final IGameController controller = mock(IGameController.class, CALLS_REAL_METHODS);
        final IMacroSystem macros = mock(IMacroSystem.class);
        when(controller.macros()).thenReturn(macros);

        ProtocolMethod.setRememberedActions.getMethod().invoke(controller);
        ProtocolMethod.nextRememberedAction.getMethod().invoke(controller);

        verify(macros).setRememberedActions();
        verify(macros).nextRememberedAction();
    }

    @Test
    public void sendUnchangedMacroEventsFromTheNetworkController() {
        final List<NetEvent> sent = new ArrayList<>();
        final IToServer server = new IToServer() {
            @Override
            public void send(final NetEvent event) {
                sent.add(event);
            }

            @Override
            public Object sendAndWait(final IdentifiableNetEvent event) {
                return null;
            }
        };
        final NetGameController controller = new NetGameController(server);

        controller.macros().setRememberedActions();
        controller.macros().nextRememberedAction();

        assertThat(sent)
                .allSatisfy(event -> assertThat(event).isInstanceOf(GuiGameEvent.class));
        assertThat(sent.stream().map(event -> ((GuiGameEvent) event).getMethod()))
                .containsExactly(ProtocolMethod.setRememberedActions, ProtocolMethod.nextRememberedAction);
    }
}

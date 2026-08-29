package dev.lumjahaj.subscription.hub.billing.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the package-private parsing rule directly with a plain
 * ObjectMapper, no Spring - the plan_entitlement rows this reads were
 * never written with billing in mind, so this proves billing tolerates
 * the shapes that already exist rather than only the ones it created.
 */
class MeterPriceResolverTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String json) throws Exception {
        return MAPPER.readTree(json);
    }

    @Test
    void parse_withUnitAmountAndIncludedQuantity_returnsThePrice() throws Exception {
        JsonNode node = json("""
                {"includedQuantity": 1000, "unitAmountCents": 2}
                """);

        Optional<MeterPrice> price = MeterPriceResolver.parse(node);

        assertThat(price).isPresent();
        assertThat(price.get().unitAmountCents()).isEqualTo(2);
        assertThat(price.get().includedQuantity()).isEqualByComparingTo("1000");
    }

    @Test
    void parse_withoutUnitAmountCents_returnsEmpty() throws Exception {
        // The shape plan_entitlement actually holds today - a plain
        // feature limit, not a priced meter.
        JsonNode node = json("""
                {"limit": 10}
                """);

        assertThat(MeterPriceResolver.parse(node)).isEmpty();
    }

    @Test
    void parse_withoutIncludedQuantity_defaultsToZero() throws Exception {
        JsonNode node = json("""
                {"unitAmountCents": 5}
                """);

        Optional<MeterPrice> price = MeterPriceResolver.parse(node);

        assertThat(price).isPresent();
        assertThat(price.get().includedQuantity()).isEqualByComparingTo("0");
    }

    @Test
    void parse_withANegativeUnitAmount_throws() throws Exception {
        JsonNode node = json("""
                {"unitAmountCents": -5}
                """);

        assertThatThrownBy(() -> MeterPriceResolver.parse(node))
                .isInstanceOf(IllegalStateException.class);
    }
}

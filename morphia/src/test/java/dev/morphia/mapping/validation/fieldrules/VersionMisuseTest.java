package dev.morphia.mapping.validation.fieldrules;


import dev.morphia.mapping.Mapper;
import org.junit.Test;
import dev.morphia.TestBase;
import dev.morphia.annotations.Version;
import dev.morphia.mapping.validation.ConstraintViolationException;
import dev.morphia.testutil.TestEntity;


/**
 * @author Uwe Schaefer, (us@thomas-daily.de)
 */
public class VersionMisuseTest extends TestBase {

    @Test(expected = ConstraintViolationException.class)
    public void testInitedPrimitive() {
        Mapper.map(Fail1.class);
    }

    @Test(expected = ConstraintViolationException.class)
    public void testInitedWrapper() {
        Mapper.map(Fail2.class);
    }

    @Test
    public void testPrimitive() {
        Mapper.map(OK1.class);
    }

    @Test
    public void testWrapper() {
        Mapper.map(OK2.class);
    }

    public static class Fail1 extends TestEntity {
        @Version
        private long hubba = 1;
    }

    public static class Fail2 extends TestEntity {
        @Version
        private Long hubba = 1L;
    }

    public static class OK1 extends TestEntity {
        @Version
        private long hubba;
    }

    public static class OK2 extends TestEntity {
        @Version
        private Long hubba;
    }
}

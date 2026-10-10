package com.example.testngdemo;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;

/**
 * A plain TestNG test class - no testng.xml suite, no groups. Proves that TestNG runs through
 * {@code kwenza test} purely via the JUnit Platform's ServiceLoader-based engine discovery
 * (org.junit.support:testng-engine), the same way JUnit Jupiter/Vintage already do.
 */
public class CalculatorTest {

    private final Calculator calculator = new Calculator();

    @Test
    public void addsTwoNumbers() {
        assertEquals(calculator.add(1, 1), 2);
    }

    @Test(dataProvider = "additions")
    public void add(int a, int b, int expected) {
        assertEquals(calculator.add(a, b), expected);
    }

    @DataProvider
    public Object[][] additions() {
        return new Object[][] {
                {0, 1, 1},
                {1, 2, 3},
                {49, 51, 100},
        };
    }
}

package org.jboss.modcluster.test.metric;

import org.jboss.modcluster.container.Engine;
import org.jboss.modcluster.load.metric.LoadMetric;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Custom load metric that reads load value from a file.
 * This allows external control of reported load for testing purposes.
 *
 * The load file should contain a line matching the pattern: LOAD: <number>
 * For example: "LOAD: 75" reports a load of 75.
 */
public class FileBasedLoadMetric implements LoadMetric {

    private String loadFilePath = "/tmp/modcluster-load.txt";
    private String parseExpression = "^LOAD: ([0-9]+)$";
    private Pattern pattern;
    private double capacity = 1000.0;
    private int weight = 1;

    public FileBasedLoadMetric() {
        this.pattern = Pattern.compile(parseExpression);
    }

    /**
     * Set the path to the file containing load information.
     */
    public void setLoadFile(String loadFilePath) {
        this.loadFilePath = loadFilePath;
    }

    /**
     * Set the regex pattern for parsing the load value from file.
     */
    public void setParseExpression(String parseExpression) {
        this.parseExpression = parseExpression;
        this.pattern = Pattern.compile(parseExpression);
    }

    /**
     * Set the capacity (maximum load value).
     */
    public void setCapacity(double capacity) {
        this.capacity = capacity;
    }

    @Override
    public double getLoad(Engine engine) throws Exception {
        File loadFile = new File(loadFilePath);

        if (!loadFile.exists()) {
            // Return 0 if file doesn't exist (no artificial load)
            return 0.0;
        }

        try {
            String content = new String(Files.readAllBytes(loadFile.toPath()));
            Matcher matcher = pattern.matcher(content.trim());

            if (matcher.find() && matcher.groupCount() >= 1) {
                String loadStr = matcher.group(1);
                double load = Double.parseDouble(loadStr);

                // Normalize to 0-1 range based on capacity
                return Math.min(load / capacity, 1.0);
            }
        } catch (IOException | NumberFormatException e) {
            // On error, return 0
            System.err.println("Error reading load from file: " + e.getMessage());
        }

        return 0.0;
    }

    @Override
    public double getCapacity() {
        return capacity;
    }

    @Override
    public void setWeight(int weight) {
        this.weight = weight;
    }

    @Override
    public int getWeight() {
        return weight;
    }
}

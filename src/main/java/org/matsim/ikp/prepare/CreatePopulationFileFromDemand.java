package org.matsim.ikp.prepare;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import org.apache.log4j.Logger;
import org.matsim.api.core.v01.population.Population;
import org.matsim.api.core.v01.population.PopulationFactory;
import org.matsim.api.core.v01.population.PopulationWriter;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.PopulationUtils;

import java.nio.file.Path;

public class CreatePopulationFileFromDemand {

    private static final Logger log = Logger.getLogger(CreatePopulationFileFromDemand.class);

    public static void main(String[] args) {
        CreatePopulationFileFromDemand.Input input = new CreatePopulationFileFromDemand.Input();
        JCommander.newBuilder().addObject(input).build().parse(args);
        String csvDemandFile = input.csvDemandFile;
        String popOutput = input.popOutput;

        log.info("Input demand file path: " + csvDemandFile);
        log.info("Output populations file path: " + popOutput);


        Population pop = createPopulation();
        writePopulation(pop, Path.of(popOutput));
    }




    public static Population createPopulation() {
        Population pop = PopulationUtils.createPopulation(ConfigUtils.createConfig());
        PopulationFactory fac = pop.getFactory();

        return pop;
    }

    public static void writePopulation(Population pop, Path path) {
        log.info("Writing population to " + path);
        new PopulationWriter(pop).write(path.toString());

        log.info("");
        log.info("Finished \uD83C\uDF89");
    }

    private class Demand{

        Demand(Path csv){
            readDemandFromCsv(csv);
        }

        private void readDemandFromCsv(Path csv){
            

        }
    }

    private static class Input {
        @Parameter(names = "-csvDemandFile")
        private String csvDemandFile;

        @Parameter(names = "-popOutput")
        private String popOutput;
    }




}

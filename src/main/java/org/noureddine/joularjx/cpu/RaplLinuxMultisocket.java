/*
 * Copyright (c) 2021-2024, Adel Noureddine, Université de Pau et des Pays de l'Adour.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the
 * GNU General Public License v3.0 only (GPL-3.0-only)
 * which accompanies this distribution, and is available at
 * https://www.gnu.org/licenses/gpl-3.0.en.html
 *
 * Author : Adel Noureddine, Fitsum Kifetew
 */

package org.noureddine.joularjx.cpu;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

import org.noureddine.joularjx.utils.JoularJXLogging;

public class RaplLinuxMultisocket implements Cpu {

    private static final Logger logger = JoularJXLogging.getLogger();

    
    public static class RaplFilePaths {
        public final Set<Path> energyFiles;
        public final Set<Path> maxEnergyFiles;

        public RaplFilePaths(Set<Path> energyFiles, Set<Path> maxEnergyFiles) {
            this.energyFiles = Collections.unmodifiableSet(energyFiles);
            this.maxEnergyFiles = Collections.unmodifiableSet(maxEnergyFiles);
        }
    }
	
    private static final String RAPL_BASE_DIR = "/sys/class/powercap/intel-rapl/";
    private static final String PACKAGE_NAME_IDENTIFIER = "package";
    
    /**
     * Finds all relevant RAPL files and organizes them into two separate sets.
     *
     * @return A {@link RaplFilePaths} object containing a set of energy files
     *         and a set of max energy range files.
     */
    private RaplFilePaths findPackageRaplFiles() {
        Path raplBasePath = fileSystem.getPath(RAPL_BASE_DIR);

        if (!Files.isDirectory(raplBasePath)) {
            System.err.println("Error: RAPL base directory not found at " + RAPL_BASE_DIR);
            return new RaplFilePaths(Collections.emptySet(), Collections.emptySet());
        }

        Set<Path> energyFiles = new HashSet<>();
        Set<Path> maxEnergyFiles = new HashSet<>();

        try (Stream<Path> packageDirs = Files.list(raplBasePath)) {
            packageDirs
                .filter(path -> path.getFileName().toString().startsWith("intel-rapl:"))
                .filter(this::isPackageDomain)
                .forEach(packageDir -> {
                    // Attempt to find and add the energy_uj file
                    Path energyFile = packageDir.resolve("energy_uj");
                    if (Files.isReadable(energyFile)) {
                        energyFiles.add(energyFile);
                    } else {
                        System.err.println("Warning: Cannot read file: " + energyFile + ". Check permissions.");
                    }

                    // Attempt to find and add the max_energy_range_uj file
                    Path maxEnergyFile = packageDir.resolve("max_energy_range_uj");
                    if (Files.isReadable(maxEnergyFile)) {
                        maxEnergyFiles.add(maxEnergyFile);
                    } else {
                        System.err.println("Warning: Cannot read file: " + maxEnergyFile + ". Check permissions.");
                    }
                });
        } catch (IOException e) {
            System.err.println("Error listing RAPL directories. This may be a permissions issue.");
            System.err.println("Try running the application with 'sudo'. Details: " + e.getMessage());
            return new RaplFilePaths(Collections.emptySet(), Collections.emptySet());
        }

        return new RaplFilePaths(energyFiles, maxEnergyFiles);
    }

    /**
     * Checks if a directory represents a RAPL package domain by reading its 'name' file.
     *
     * @param domainPath The path to a potential RAPL package directory.
     * @return {@code true} if the 'name' file indicates it's a package domain.
     */
    private boolean isPackageDomain(Path domainPath) {
        Path nameFile = domainPath.resolve("name");
        if (!Files.isReadable(nameFile)) {
            System.err.println("Warning: Cannot read 'name' file at " + nameFile + ". Try running with 'sudo'.");
            return false;
        }
        try {
            return new String(Files.readAllBytes(nameFile)).trim().startsWith(PACKAGE_NAME_IDENTIFIER);
        } catch (IOException e) {
            System.err.println("Error reading name file in " + domainPath + ": " + e.getMessage());
            return false;
        }
    }
    
    
    
//    static final String RAPL_PSYS = "/sys/class/powercap/intel-rapl/intel-rapl:1/energy_uj";
//
//    static final String RAPL_PKG = "/sys/class/powercap/intel-rapl/intel-rapl:0/energy_uj";
//
//    static final String RAPL_DRAM = "/sys/class/powercap/intel-rapl/intel-rapl:0/intel-rapl:0:2/energy_uj";
//
//    static final String RAPL_PSYS_MAX = "/sys/class/powercap/intel-rapl/intel-rapl:1/max_energy_range_uj";
//
//    static final String RAPL_PKG_MAX = "/sys/class/powercap/intel-rapl/intel-rapl:0/max_energy_range_uj";
//
//    static final String RAPL_DRAM_MAX = "/sys/class/powercap/intel-rapl/intel-rapl:0/intel-rapl:0:2/max_energy_range_uj";

    /**
     * RAPL files existing on the current system. All files in this list will be used for reading the
     * energy values.
     */
    private final Set<Path> raplFilesToRead = new HashSet<Path>(3);

    /**
     * RAPL max values files existing on the current system. All files in this list will be used for reading the energy values.
     */
    private final Set<Path> maxRaplFilesToRead = new HashSet<Path>(3);

    /**
     * Filesystem where the RAPL files are located.
     */
    private final FileSystem fileSystem;

    /**
     * Create a new energy measurement via RAPL. The files will be read from the default filesystem.
     */
    public RaplLinuxMultisocket() {
        this(FileSystems.getDefault());
    }

    /**
     * Create a new energy measurement via RAPL. The files will be read from the passed filesystem.
     *
     * @param fileSystem The filesystem to use for reading the RAPL files
     */
    RaplLinuxMultisocket(final FileSystem fileSystem) {
        this.fileSystem = fileSystem;
    }

    /**
     * Check which RAPL files are available on the system to read the energy values from.
     */
    @Override
    public void initialize() {
        RaplFilePaths raplFiles = findPackageRaplFiles();
    	
        if (raplFiles.energyFiles.isEmpty()) {
        	logger.log(Level.SEVERE, "Found no RAPL files to read the energy measurement from. Exit ...");
            System.exit(1);
        }
        
        for (Path raplFile : raplFiles.energyFiles) {
        	raplFilesToRead.add(raplFile);
        }
        
        for (Path maxEnergyFile : raplFiles.maxEnergyFiles) {
        	maxRaplFilesToRead.add(maxEnergyFile);
        }
        
    }

    /**
     * Get energy readings from RAPL through powercap
     * Calculates the best energy reading as supported by CPU (psys, or pkg+dram, or pkg)
     * @return Energy readings from RAPL
     */
    @Override
    public double getCurrentPower(final double cpuLoad) {
        double energyData = 0.0;

        for (final Path raplFile : raplFilesToRead) {
            try {
                double reading = Double.parseDouble(Files.readString(raplFile));
                logger.info(String.format("Read avalue %f from file %s", reading, raplFile.toAbsolutePath().toString()));
				energyData += reading;
            } catch (IOException exception) {
                logger.throwing(getClass().getName(), "getCurrentPower", exception);
            }
        }

        // Divide by 1 million to convert microJoules to Joules
        return energyData / 1000000;
    }

    /**
     * Get max energy value of RAPL interface through powercap
     * @return Maximum energy value of RAPL interface
     */
    @Override
    public double getMaxPower(final double cpuLoad) {
        double energyData = 0.0;

        for (final Path raplFile : maxRaplFilesToRead) {
            try {
                energyData += Double.parseDouble(Files.readString(raplFile));
            } catch (IOException exception) {
                logger.throwing(getClass().getName(), "getMaxPower", exception);
            }
        }

        // Divide by 1 million to convert microJoules to Joules
        return energyData / 1000000;
    }

    /**
     * Returns the
     *
     * @return Energy readings from RAPL
     */
    @Override
    public double getInitialPower() {
        return getCurrentPower(0);
    }

    @Override
    public void close() {
        // Nothing to do for RAPL Linux
    }
}

package za.co.entelect.challenge.core.engine;

import za.co.entelect.challenge.commands.DoNothingCommand;
import za.co.entelect.challenge.commands.PlaceBuildingCommand;
import za.co.entelect.challenge.config.GameConfig;
import za.co.entelect.challenge.entities.Building;
import za.co.entelect.challenge.entities.Cell;
import za.co.entelect.challenge.entities.TowerDefenseGameMap;
import za.co.entelect.challenge.entities.TowerDefensePlayer;
import za.co.entelect.challenge.enums.BuildingType;
import za.co.entelect.challenge.enums.PlayerType;
import za.co.entelect.challenge.game.contracts.command.RawCommand;
import za.co.entelect.challenge.game.contracts.exceptions.InvalidCommandException;
import za.co.entelect.challenge.game.contracts.game.GamePlayer;
import za.co.entelect.challenge.game.contracts.game.GameRoundProcessor;
import za.co.entelect.challenge.game.contracts.map.GameMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Hashtable;
import java.util.Map;
import java.util.stream.IntStream;

public class TowerDefenseRoundProcessor implements GameRoundProcessor {

    private static final Logger log = LogManager.getLogger(TowerDefenseRoundProcessor.class);

    private TowerDefenseGameMap towerDefenseGameMap;

    @Override
    public boolean processRound(GameMap gameMap, Hashtable<GamePlayer, RawCommand> commands) {
        towerDefenseGameMap = (TowerDefenseGameMap) gameMap;
        towerDefenseGameMap.clearErrorList();

        processCommands(commands);

        constructBuildings();

        createMissilesFromGuns();
        calculateMissileMovement();
        removeDeadEntities();

        addResources();

        return true;
    }

    @Override
    public ArrayList<String> getErrorList() {
        return towerDefenseGameMap.getErrorList();
    }

    private void constructBuildings() {
        towerDefenseGameMap.getBuildings().stream()
                .filter(b -> !b.isConstructed())
                .forEach(Building::decreaseConstructionTimeLeft);
    }

    private void createMissilesFromGuns() {
        towerDefenseGameMap.getBuildings().stream()
                .filter(Building::isConstructed)
                .forEach(b -> towerDefenseGameMap.addMissileFromBuilding(b));
    }

    private void processCommands(Hashtable<GamePlayer, RawCommand> commands) {
        for (Map.Entry<GamePlayer, RawCommand> gamePlayerCommandEntry : commands.entrySet()) {
            GamePlayer player = gamePlayerCommandEntry.getKey();
            RawCommand command = gamePlayerCommandEntry.getValue();
            parseAndExecuteCommand(command, player);
        }
    }

    private int getBuildingGeneratedEnergyForPlayer(PlayerType player) {
        return towerDefenseGameMap.getBuildings().stream()
                .filter(b -> b.getPlayerType() == player && b.isConstructed())
                .mapToInt(Building::getEnergyGeneratedPerTurn)
                .sum();
    }

    private void addResources() {
        towerDefenseGameMap.getTowerDefensePlayers()
                .forEach(p -> {
                    int energy = GameConfig.getRoundIncomeEnergy()
                            + getBuildingGeneratedEnergyForPlayer(p.getPlayerType());
                    try {
                        p.addEnergy(energy);
                    } catch (Exception e) {
                        log.error(e);
                    }
                    p.addScore(energy * GameConfig.getEnergyScoreMultiplier());
                });
    }

    private void removeDeadEntities() {
        new ArrayList<>(towerDefenseGameMap.getBuildings()).stream()
                .filter(b -> b.getHealth() == 0)
                .forEach(b -> towerDefenseGameMap.removeBuilding(b));

        new ArrayList<>(towerDefenseGameMap.getMissiles()).stream()
                .filter(p -> p.getSpeed() == 0)
                .forEach(p -> towerDefenseGameMap.removeMissile(p));
    }

    private static boolean positionMatch(Cell a, Cell b) {
        return (a.getY() == b.getY()) && (a.getX() == b.getX());
    }

    private void calculateMissileMovement() {
        towerDefenseGameMap.getMissiles()
                .forEach(missile ->
                        IntStream.rangeClosed(1, missile.getSpeed()) // higher speed bullets
                                .forEach(i -> {
                                    towerDefenseGameMap.moveMissileSingleSpace(missile);
                                    towerDefenseGameMap.getBuildings().stream()
                                            .filter(b -> b.isConstructed()
                                                    && positionMatch(missile, b)
                                                    && !b.isPlayers(missile.getPlayerType())
                                                    && b.getHealth() > 0)
                                            .forEach(b -> {
                                                TowerDefensePlayer missileOwner = null;
                                                try {
                                                    missileOwner = towerDefenseGameMap.getPlayer(missile.getPlayerType());
                                                } catch (Exception e) {
                                                    log.error(e);
                                                }
                                                b.damageSelf(missile, missileOwner);
                                            });
                                })
                );
    }

    // Converts Raw Commands into Commands the game engine can understand
    private void parseAndExecuteCommand(RawCommand command, GamePlayer player) {
        String commandSting = command.getCommand();

        String[] commandLine = commandSting.split(",");

        DoNothingCommand doNothingCommand = new DoNothingCommand();

        TowerDefensePlayer towerDefensePlayer = (TowerDefensePlayer) player;

        if (commandLine.length == 1) {
            doNothingCommand.performCommand(towerDefenseGameMap, player);
            return;
        }
        if (commandLine.length != 3) {
            doNothingCommand.performCommand(towerDefenseGameMap, player);
            towerDefenseGameMap.addErrorToErrorList(String.format(
                    "Unable to parse command expected 3 parameters, got %d", commandLine.length), towerDefensePlayer);
        }
        try {
            int positionX = Integer.parseInt(commandLine[0]);
            int positionY = Integer.parseInt(commandLine[1]);
            BuildingType buildingType = BuildingType.values()[Integer.parseInt(commandLine[2])];

            new PlaceBuildingCommand(positionX, positionY, buildingType).performCommand(towerDefenseGameMap, player);
        } catch (NumberFormatException e) {
            doNothingCommand.performCommand(towerDefenseGameMap, player);
            towerDefenseGameMap.addErrorToErrorList(String.format(
                    "Unable to parse command entries, all parameters should be integers. Received:%s",
                    commandSting), towerDefensePlayer);

            log.error(towerDefenseGameMap.getErrorList().get(towerDefenseGameMap.getErrorList().size()));
        } catch (IllegalArgumentException e) {
            doNothingCommand.performCommand(towerDefenseGameMap, player);
            towerDefenseGameMap.addErrorToErrorList(String.format(
                    "Unable to parse building type: Expected 0[Defense], 1[Attack], 2[Energy]. Received:%s",
                    commandLine[2]), towerDefensePlayer);

            log.error(towerDefenseGameMap.getErrorList().get(towerDefenseGameMap.getErrorList().size()));
        } catch (InvalidCommandException e) {
            doNothingCommand.performCommand(towerDefenseGameMap, player);
            towerDefenseGameMap.addErrorToErrorList(
                    "Invalid command received: " + e.getMessage(), towerDefensePlayer);

            log.error(towerDefenseGameMap.getErrorList().get(towerDefenseGameMap.getErrorList().size()));
        } catch (IndexOutOfBoundsException e) {
            doNothingCommand.performCommand(towerDefenseGameMap, player);
            towerDefenseGameMap.addErrorToErrorList(String.format(
                    "Out of map bounds, X:%s Y: %s", commandLine[0], commandLine[1]), towerDefensePlayer);

            log.error(towerDefenseGameMap.getErrorList().get(towerDefenseGameMap.getErrorList().size()));
        }
    }
}

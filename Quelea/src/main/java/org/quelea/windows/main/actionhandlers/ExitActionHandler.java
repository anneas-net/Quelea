/*
 * This file is part of Quelea, free projection software for churches.
 *
 *
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.quelea.windows.main.actionhandlers;


import java.util.logging.Level;
import java.util.logging.Logger;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.event.Event;
import javafx.event.EventHandler;
import javafx.stage.WindowEvent;
import org.javafx.dialog.Dialog;
import org.quelea.data.Schedule;
import org.quelea.data.ScheduleSaver;
import org.quelea.data.displayable.Displayable;
import org.quelea.data.displayable.IndexedDisplayable;
import org.quelea.data.displayable.PresentationDisplayable;
import org.quelea.data.powerpoint.OOUtils;
import org.quelea.services.languages.LabelGrabber;
import org.quelea.services.utils.LoggerUtils;
import org.quelea.services.utils.QueleaProperties;
import org.quelea.services.utils.SceneInfo;
import org.quelea.windows.main.MainWindow;
import org.quelea.windows.main.QueleaApp;
import org.quelea.windows.main.toolbars.MainToolbar;
import org.quelea.windows.presentation.PowerPointHandler;

/**
 * The exit action listener - called when the user requests they wish to exit
 * Quelea.
 * <p/>
 * @author Michael
 */
public class ExitActionHandler implements EventHandler<ActionEvent> {

    private static final Logger LOGGER = LoggerUtils.getLogger();

    /**
     * Call this method when the event is fired.
     */
    @Override
    public void handle(ActionEvent t) {
        exit(t);
    }

    private volatile boolean block = false;
    private boolean exitWithoutSaving;

    /**
     * Process the necessary logic to cleanly exit from Quelea.
     *
     * @param t the event that caused the exit.
     */
    public void exit(Event t) {
        LOGGER.log(Level.INFO, "exit() called");
        block = false;
        MainWindow mainWindow = QueleaApp.get().getMainWindow();
        t.consume();
        Schedule schedule = mainWindow.getMainPanel().getSchedulePanel().getScheduleList().getSchedule();
        if (!schedule.isEmpty() && schedule.isModified()) {
            exitWithoutSaving = false;
            Dialog d = Dialog.buildConfirmation(LabelGrabber.INSTANCE.getLabel("save.before.exit.title"), LabelGrabber.INSTANCE.getLabel("save.before.exit.text")).addYesButton(t14 -> {
                // Save the schedule, and only exit once the save has finished
                // successfully. Previously the UI thread spun in a sleep loop
                // waiting for the save; if saving threw an exception the flag
                // was never reset and Quelea hung forever on exit.
                new ScheduleSaver().saveSchedule(false, success -> {
                    if (success) {
                        Platform.runLater(() -> doExit(mainWindow));
                    }
                });
            }).addNoButton(t13 -> exitWithoutSaving = true).addCancelButton(t12 -> {
                //No need to do anything
            }).build();
            d.showAndWait();
            if (exitWithoutSaving) {
                doExit(mainWindow);
            }
            return;
        }
        doExit(mainWindow);
    }

    /**
     * Clean up and exit. Whatever happens during clean-up, the process is
     * always terminated - otherwise an error here would leave an invisible
     * Quelea process running (still holding the database lock), which then
     * stops Quelea from starting again until it's killed in Task Manager.
     */
    private void doExit(MainWindow mainWindow) {
        try {
            if (cleanUp(mainWindow)) {
                return; // waiting for a recording to finish saving; that path exits itself
            }
        } catch (Throwable ex) {
            LOGGER.log(Level.SEVERE, "Error while cleaning up on exit", ex);
        }
        System.exit(0);
    }

    /**
     * @return true if exit has been deferred until a recording is saved.
     */
    private boolean cleanUp(MainWindow mainWindow) {
        LOGGER.log(Level.INFO, "Saving window position...");
        QueleaProperties.get().setSceneInfo(new SceneInfo(mainWindow.getX(), mainWindow.getY(), mainWindow.getWidth(), mainWindow.getHeight(), mainWindow.isMaximized()));
        QueleaProperties.get().setMainDivPos(mainWindow.getMainPanel().getMainDivPos());
        QueleaProperties.get().setPrevLiveDivPos(mainWindow.getMainPanel().getPrevLiveDivPos());
        QueleaProperties.get().setCanvasDivPos(mainWindow.getMainPanel().getLivePanel().getLyricsPanel().getSplitPane().getDividerPositions()[0]);
        QueleaProperties.get().setPreviewDivPos(mainWindow.getMainPanel().getPreviewPanel().getLyricsPanel().getSplitPane().getDividerPositions()[0]);
        QueleaProperties.get().setLibraryDivPos(mainWindow.getMainPanel().getLibraryDivPos());
        LOGGER.log(Level.INFO, "Hiding main window...");
        mainWindow.hide();
        LOGGER.log(Level.INFO, "Cleaning up displayables before exiting..");
        for (IndexedDisplayable d : mainWindow.getMainPanel().getSchedulePanel().getScheduleList().itemsProperty().get()) {
            if (d != null) {
                LOGGER.log(Level.INFO, "Cleaning up {0}", d.getClass());
                try {
                    d.displayable().dispose();
                } catch (Throwable ex) {
                    LOGGER.log(Level.WARNING, "Couldn't clean up " + d.getClass(), ex);
                }
            }
        }

        LOGGER.log(Level.INFO, "Try to close OOfice if opened");
        OOUtils.closeOOApp();
        if (QueleaApp.get().getMobileLyricsServer() != null) {
            LOGGER.log(Level.INFO, "Stopping mobile lyrics server");
            QueleaApp.get().getMobileLyricsServer().stop();
        }
        if (QueleaApp.get().getRemoteControlServer() != null) {
            LOGGER.log(Level.INFO, "Stopping remote control server");
            QueleaApp.get().getRemoteControlServer().stop();
        }
        if (QueleaApp.get().getMainWindow().getMainPanel().getLivePanel().getDisplayable() instanceof PresentationDisplayable) {
            LOGGER.log(Level.INFO, "Closing open PowerPoint presentations");
            PowerPointHandler.closePresentation();
        }

        LOGGER.log(Level.INFO, "Checking if Quelea currently is recording audio");
        MainToolbar toolbar = mainWindow.getMainToolbar();
        RecordingsHandler recHandler = toolbar.getRecordButtonHandler() == null ? null : toolbar.getRecordButtonHandler().getRecordingsHandler();
        if (recHandler != null) {
            if (recHandler.getIsRecording()) {
                block = true;
                Dialog d = Dialog.buildConfirmation(LabelGrabber.INSTANCE.getLabel("save.recording.before.exit.title"), LabelGrabber.INSTANCE.getLabel("save.recording.before.exit.message"))
                        .addYesButton((ActionEvent t1) -> toolbar.stopRecording()).addNoButton((ActionEvent t1) -> System.exit(0)).build();
                d.setOnCloseRequest((WindowEvent we) -> System.exit(0));
                Thread thread = new Thread(() -> {
                    while (block) {
                        try {
                            Thread.sleep(500);
                            if (recHandler.getFinishedSaving()) {
                                Platform.runLater(() -> d.close());
                                if (QueleaProperties.get().getConvertRecordings()) {
                                    boolean converting = recHandler.isConverting();
                                    if (!converting) {
                                        block = false;
                                        System.exit(0);
                                    }
                                } else {
                                    block = false;
                                    System.exit(0);
                                }
                            }
                        } catch (InterruptedException ex) {
                        }
                    }
                });
                thread.setDaemon(true);
                thread.start();
                d.showAndWait();
                return true; //Don't exit until the recording is saved and converted
            }
        }
        return false;
    }
}

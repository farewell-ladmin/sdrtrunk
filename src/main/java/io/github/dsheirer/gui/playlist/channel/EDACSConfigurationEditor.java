/*
 * *****************************************************************************
 * Copyright (C) 2014-2025 Dennis Sheirer
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
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 * ****************************************************************************
 */

package io.github.dsheirer.gui.playlist.channel;

import io.github.dsheirer.controller.channel.map.ChannelMap;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.gui.playlist.channelMap.ViewChannelMapEditorRequest;
import io.github.dsheirer.gui.playlist.decoder.AuxDecoderConfigurationEditor;
import io.github.dsheirer.gui.playlist.eventlog.EventLogConfigurationEditor;
import io.github.dsheirer.gui.playlist.record.RecordConfigurationEditor;
import io.github.dsheirer.gui.playlist.source.FrequencyEditor;
import io.github.dsheirer.gui.playlist.source.SourceConfigurationEditor;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.config.AuxDecodeConfiguration;
import io.github.dsheirer.module.decode.config.DecodeConfiguration;
import io.github.dsheirer.module.decode.edacs.DecodeConfigEDACS;
import io.github.dsheirer.module.log.EventLogType;
import io.github.dsheirer.module.log.config.EventLogConfiguration;
import io.github.dsheirer.playlist.PlaylistManager;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.record.RecorderType;
import io.github.dsheirer.record.config.RecordConfiguration;
import io.github.dsheirer.source.config.SourceConfiguration;
import io.github.dsheirer.source.tuner.manager.TunerManager;
import java.util.ArrayList;
import java.util.List;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TitledPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * EDACS channel configuration editor
 */
public class EDACSConfigurationEditor extends ChannelConfigurationEditor
{
    private final static Logger mLog = LoggerFactory.getLogger(EDACSConfigurationEditor.class);
    private TitledPane mAuxDecoderPane;
    private TitledPane mDecoderPane;
    private TitledPane mEventLogPane;
    private TitledPane mRecordPane;
    private TitledPane mSourcePane;
    private SourceConfigurationEditor mSourceConfigurationEditor;
    private AuxDecoderConfigurationEditor mAuxDecoderConfigurationEditor;
    private EventLogConfigurationEditor mEventLogConfigurationEditor;
    private RecordConfigurationEditor mRecordConfigurationEditor;
    private ComboBox<ChannelMap> mChannelMapComboBox;
    private Button mChannelMapEditButton;

    public EDACSConfigurationEditor(PlaylistManager playlistManager, TunerManager tunerManager,
                                     UserPreferences userPreferences, IFilterProcessor filterProcessor)
    {
        super(playlistManager, tunerManager, userPreferences, filterProcessor);
        getTitledPanesBox().getChildren().add(getSourcePane());
        getTitledPanesBox().getChildren().add(getDecoderPane());
        getTitledPanesBox().getChildren().add(getAuxDecoderPane());
        getTitledPanesBox().getChildren().add(getEventLogPane());
        getTitledPanesBox().getChildren().add(getRecordPane());
    }

    @Override
    public DecoderType getDecoderType()
    {
        return DecoderType.EDACS;
    }

    private TitledPane getSourcePane()
    {
        if(mSourcePane == null)
        {
            mSourcePane = new TitledPane("Source", getSourceConfigurationEditor());
            mSourcePane.setExpanded(true);
        }

        return mSourcePane;
    }

    private TitledPane getDecoderPane()
    {
        if(mDecoderPane == null)
        {
            mDecoderPane = new TitledPane();
            mDecoderPane.setText("Decoder: EDACS");
            mDecoderPane.setExpanded(false);

            GridPane gridPane = new GridPane();
            gridPane.setPadding(new Insets(10, 10, 10, 10));
            gridPane.setHgap(10);
            gridPane.setVgap(10);

            Label channelMapLabel = new Label("Channel Map");
            GridPane.setHalignment(channelMapLabel, HPos.RIGHT);
            GridPane.setConstraints(channelMapLabel, 0, 0);
            gridPane.getChildren().add(channelMapLabel);

            GridPane.setConstraints(getChannelMapComboBox(), 1, 0, 2, 1, HPos.LEFT, null, null, null);
            gridPane.getChildren().add(getChannelMapComboBox());

            GridPane.setConstraints(getChannelMapEditButton(), 3, 0);
            gridPane.getChildren().add(getChannelMapEditButton());

            mDecoderPane.setContent(gridPane);
        }

        return mDecoderPane;
    }

    private TitledPane getEventLogPane()
    {
        if(mEventLogPane == null)
        {
            mEventLogPane = new TitledPane("Logging", getEventLogConfigurationEditor());
            mEventLogPane.setExpanded(false);
        }

        return mEventLogPane;
    }

    private TitledPane getAuxDecoderPane()
    {
        if(mAuxDecoderPane == null)
        {
            mAuxDecoderPane = new TitledPane("Additional Decoders", getAuxDecoderConfigurationEditor());
            mAuxDecoderPane.setExpanded(false);
        }

        return mAuxDecoderPane;
    }

    private TitledPane getRecordPane()
    {
        if(mRecordPane == null)
        {
            mRecordPane = new TitledPane();
            mRecordPane.setText("Recording");
            mRecordPane.setExpanded(false);

            Label notice = new Label("Note: use aliases to control call audio recording");
            notice.setPadding(new Insets(10, 10, 0, 10));

            VBox vBox = new VBox();
            vBox.getChildren().addAll(getRecordConfigurationEditor(), notice);

            mRecordPane.setContent(vBox);
        }

        return mRecordPane;
    }

    private SourceConfigurationEditor getSourceConfigurationEditor()
    {
        if(mSourceConfigurationEditor == null)
        {
            mSourceConfigurationEditor = new FrequencyEditor(mTunerManager);

            mSourceConfigurationEditor.modifiedProperty()
                .addListener((observable, oldValue, newValue) -> modifiedProperty().set(true));
        }

        return mSourceConfigurationEditor;
    }

    private ComboBox<ChannelMap> getChannelMapComboBox()
    {
        if(mChannelMapComboBox == null)
        {
            mChannelMapComboBox = new ComboBox<>();
            mChannelMapComboBox.setMaxWidth(Double.MAX_VALUE);
            mChannelMapComboBox.setDisable(true);
            mChannelMapComboBox.setTooltip(new Tooltip("Select a channel map for EDACS LCN to frequency mapping"));
            mChannelMapComboBox.setItems(getPlaylistManager().getChannelMapModel().getChannelMaps());
            mChannelMapComboBox.setDisable(true);
            mChannelMapComboBox.getSelectionModel().selectedItemProperty()
                .addListener((observable, oldValue, newValue) -> modifiedProperty().set(true));
        }

        return mChannelMapComboBox;
    }

    private Button getChannelMapEditButton()
    {
        if(mChannelMapEditButton == null)
        {
            mChannelMapEditButton = new Button("Channel Map Editor");
            mChannelMapEditButton.setTooltip(new Tooltip("Open the channel map editor to add/edit LCN frequency maps"));
            mChannelMapEditButton.setOnAction(new EventHandler<ActionEvent>()
            {
                @Override
                public void handle(ActionEvent event)
                {
                    String channelMapName = null;
                    if(getChannelMapComboBox().getSelectionModel().getSelectedItem() != null)
                    {
                        channelMapName = getChannelMapComboBox().getSelectionModel().getSelectedItem().getName();
                    }
                    MyEventBus.getGlobalEventBus().post(new ViewChannelMapEditorRequest(channelMapName));
                }
            });
        }

        return mChannelMapEditButton;
    }

    private EventLogConfigurationEditor getEventLogConfigurationEditor()
    {
        if(mEventLogConfigurationEditor == null)
        {
            List<EventLogType> types = new ArrayList<>();
            types.add(EventLogType.CALL_EVENT);
            types.add(EventLogType.DECODED_MESSAGE);

            mEventLogConfigurationEditor = new EventLogConfigurationEditor(types);
            mEventLogConfigurationEditor.setPadding(new Insets(5,5,5,5));
            mEventLogConfigurationEditor.modifiedProperty().addListener((observable, oldValue, newValue) -> modifiedProperty().set(true));
        }

        return mEventLogConfigurationEditor;
    }

    private AuxDecoderConfigurationEditor getAuxDecoderConfigurationEditor()
    {
        if(mAuxDecoderConfigurationEditor == null)
        {
            List<DecoderType> types = new ArrayList<>();
            types.add(DecoderType.FLEETSYNC2);
            types.add(DecoderType.MDC1200);
            mAuxDecoderConfigurationEditor = new AuxDecoderConfigurationEditor(types);
            mAuxDecoderConfigurationEditor.setPadding(new Insets(5,5,5,5));
            mAuxDecoderConfigurationEditor.modifiedProperty().addListener((observable, oldValue, newValue) -> modifiedProperty().set(true));
        }

        return mAuxDecoderConfigurationEditor;
    }

    private RecordConfigurationEditor getRecordConfigurationEditor()
    {
        if(mRecordConfigurationEditor == null)
        {
            List<RecorderType> types = new ArrayList<>();
            types.add(RecorderType.BASEBAND);
            mRecordConfigurationEditor = new RecordConfigurationEditor(types);
            mRecordConfigurationEditor.setDisable(true);
            mRecordConfigurationEditor.modifiedProperty()
                .addListener((observable, oldValue, newValue) -> modifiedProperty().set(true));
        }

        return mRecordConfigurationEditor;
    }

    @Override
    protected void setDecoderConfiguration(DecodeConfiguration config)
    {
        getChannelMapComboBox().setDisable(config == null);

        if(config instanceof DecodeConfigEDACS decodeConfigEDACS)
        {
            String channelMapName = decodeConfigEDACS.getChannelMapName();
            if(channelMapName != null)
            {
                for(ChannelMap channelMap: getChannelMapComboBox().getItems())
                {
                    if(channelMap.getName().contentEquals(channelMapName))
                    {
                        getChannelMapComboBox().getSelectionModel().select(channelMap);
                        break;
                    }
                }
            }
            else
            {
                getChannelMapComboBox().getSelectionModel().select(null);
            }
        }
        else
        {
            getChannelMapComboBox().getSelectionModel().select(null);
        }
    }

    @Override
    protected void saveDecoderConfiguration()
    {
        DecodeConfigEDACS config;

        if(getItem().getDecodeConfiguration() instanceof DecodeConfigEDACS)
        {
            config = (DecodeConfigEDACS)getItem().getDecodeConfiguration();
        }
        else
        {
            config = new DecodeConfigEDACS();
        }

        ChannelMap selected = getChannelMapComboBox().getSelectionModel().getSelectedItem();
        if(selected != null)
        {
            config.setChannelMapName(selected.getName());
        }
        else
        {
            config.setChannelMapName(null);
        }

        getItem().setDecodeConfiguration(config);
    }

    @Override
    protected void setEventLogConfiguration(EventLogConfiguration config)
    {
        getEventLogConfigurationEditor().setItem(config);
    }

    @Override
    protected void saveEventLogConfiguration()
    {
        getEventLogConfigurationEditor().save();

        if(getEventLogConfigurationEditor().getItem().getLoggers().isEmpty())
        {
            getItem().setEventLogConfiguration(null);
        }
        else
        {
            getItem().setEventLogConfiguration(getEventLogConfigurationEditor().getItem());
        }
    }

    @Override
    protected void setAuxDecoderConfiguration(AuxDecodeConfiguration config)
    {
        getAuxDecoderConfigurationEditor().setItem(config);
    }

    @Override
    protected void saveAuxDecoderConfiguration()
    {
        getAuxDecoderConfigurationEditor().save();

        if(getAuxDecoderConfigurationEditor().getItem().getAuxDecoders().isEmpty())
        {
            getItem().setAuxDecodeConfiguration(null);
        }
        else
        {
            getItem().setAuxDecodeConfiguration(getAuxDecoderConfigurationEditor().getItem());
        }
    }

    @Override
    protected void setRecordConfiguration(RecordConfiguration config)
    {
        getRecordConfigurationEditor().setDisable(config == null);
        getRecordConfigurationEditor().setItem(config);
    }

    @Override
    protected void saveRecordConfiguration()
    {
        getRecordConfigurationEditor().save();
        RecordConfiguration config = getRecordConfigurationEditor().getItem();
        getItem().setRecordConfiguration(config);
    }

    @Override
    protected void setSourceConfiguration(SourceConfiguration config)
    {
        getSourceConfigurationEditor().setSourceConfiguration(config);
    }

    @Override
    protected void saveSourceConfiguration()
    {
        getSourceConfigurationEditor().save();
        SourceConfiguration sourceConfiguration = getSourceConfigurationEditor().getSourceConfiguration();
        getItem().setSourceConfiguration(sourceConfiguration);
    }
}

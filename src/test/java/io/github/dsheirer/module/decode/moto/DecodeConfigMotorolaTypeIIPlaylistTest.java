/*
 * *****************************************************************************
 * Copyright (C) 2014-2026 Dennis Sheirer
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

package io.github.dsheirer.module.decode.moto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.JacksonXmlModule;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.playlist.PlaylistManager;
import io.github.dsheirer.playlist.PlaylistV2;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests that a playlist saved before the default voice mode setting was removed still loads.
 */
class DecodeConfigMotorolaTypeIIPlaylistTest
{
    private static final String PLAYLIST = """
        <playlist version="4">
          <channel name="Metro" site="Simulcast" system="Test" enabled="false" order="1">
            <alias_list_name>Test</alias_list_name>
            <source_configuration type="sourceConfigTuner" frequency="854562500" source_type="TUNER"/>
            <decode_configuration type="decodeConfigMotorolaTypeII">
              <traffic_channel_pool_size>10</traffic_channel_pool_size>
              <bandplan_type>EIGHT_HUNDRED_REBANDED</bandplan_type>
              <obt_base_frequency>0.0</obt_base_frequency>
              <analog_deemphasis>US_300</analog_deemphasis>
              <obt_offset>0</obt_offset>
              <obt_spacing>0.0</obt_spacing>
              <default_voice_mode>ANALOG</default_voice_mode>
            </decode_configuration>
          </channel>
        </playlist>
        """;

    @Test
    void playlistWithTheRemovedVoiceModeSettingLoads(@TempDir Path directory) throws Exception
    {
        Path path = directory.resolve("playlist.xml");
        Files.writeString(path, PLAYLIST);

        assertTrue(PlaylistManager.isPlaylist(path));

        JacksonXmlModule xmlModule = new JacksonXmlModule();
        xmlModule.setDefaultUseWrapper(false);
        ObjectMapper objectMapper = new XmlMapper(xmlModule)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        PlaylistV2 playlist = objectMapper.readValue(path.toFile(), PlaylistV2.class);

        assertEquals(1, playlist.getChannels().size());
        Channel channel = playlist.getChannels().get(0);
        DecodeConfigMotorolaTypeII config = assertInstanceOf(DecodeConfigMotorolaTypeII.class,
            channel.getDecodeConfiguration());
        assertEquals(10, config.getTrafficChannelPoolSize());
        assertEquals(BandplanType.EIGHT_HUNDRED_REBANDED, config.getBandplanType());
        assertEquals(AnalogDeemphasis.US_300, config.getAnalogDeemphasis());
    }
}

package net.iakovlev.timeshape;

import com.esri.core.geometry.Envelope2D;
import net.iakovlev.timeshape.proto.Geojson;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.utils.IOUtils;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Class {@link TimeZoneEngine} is used to lookup the instance of
 * {@link java.time.ZoneId} based on latitude and longitude.
 */
public final class TimeZoneEngine {

    private final Index index;

    private final static double MIN_LAT = -90;
    private final static double MIN_LON = -180;
    private final static double MAX_LAT = 90;
    private final static double MAX_LON = 180;

    private final static Logger log = LoggerFactory.getLogger(TimeZoneEngine.class);

    private TimeZoneEngine(Index index) {
        this.index = index;
    }

    private static void validateCoordinates(double minLat, double minLon, double maxLat, double maxLon) {
        List<String> errors = new ArrayList<>();
        if (minLat < MIN_LAT || minLat > MAX_LAT) {
            errors.add(String.format("minimum latitude %f is out of range: must be -90 <= latitude <= 90;", minLat));
        }
        if (maxLat < MIN_LAT || maxLat > MAX_LAT) {
            errors.add(String.format("maximum latitude %f is out of range: must be -90 <= latitude <= 90;", maxLat));
        }
        if (minLon < MIN_LON || minLon > MAX_LON) {
            errors.add(String.format("minimum longitude %f is out of range: must be -180 <= longitude <= 180;", minLon));
        }
        if (maxLon < MIN_LON || maxLon > MAX_LON) {
            errors.add(String.format("maximum longitude %f is out of range: must be -180 <= longitude <= 180;", maxLon));
        }
        if (minLat > maxLat) {
            errors.add(String.format("maximum latitude %f is less than minimum latitude %f;", maxLat, minLat));
        }
        if (minLon > maxLon) {
            errors.add(String.format("maximum longitude %f is less than minimum longitude %f;", maxLon, minLon));
        }
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join(" ", errors));
        }
    }

    /**
     * Queries the {@link TimeZoneEngine} for a {@link java.time.ZoneId}
     * based on geo coordinates.
     *
     * @param latitude  latitude part of query
     * @param longitude longitude part of query
     * @return {@code Optional<ZoneId>#of(ZoneId)} if input corresponds
     * to some zone, or {@link Optional#empty()} otherwise.
     */
    public Optional<ZoneId> query(double latitude, double longitude) {
        return index.query(latitude, longitude);
    }

    /**
     * Returns all the time zones that can be looked up.
     *
     * @return all the time zones that can be looked up.
     */
    public List<ZoneId> getKnownZoneIds() {
        return index.getKnownZoneIds();
    }

    /**
     * Creates a new instance of {@link TimeZoneEngine} and initializes it.
     * This is a blocking long running operation.
     *
     * @return an initialized instance of {@link TimeZoneEngine}
     */
    public static TimeZoneEngine initialize() {
        return initialize(MIN_LAT, MIN_LON, MAX_LAT, MAX_LON);
    }

    /**
     * Creates a new instance of {@link TimeZoneEngine} and initializes it.
     * This is a blocking long running operation.
     *
     * @return an initialized instance of {@link TimeZoneEngine}
     */
    public static TimeZoneEngine initialize(double minLat, double minLon, double maxLat, double maxLon) {
        log.info("Initializing with bounding box: {}, {}, {}, {}", minLat, minLon, maxLat, maxLon);
        validateCoordinates(minLat, minLon, maxLat, maxLon);
        try (InputStream resourceAsStream = TimeZoneEngine.class.getResourceAsStream("/output.pb.7z");
             SeekableInMemoryByteChannel channel = new SeekableInMemoryByteChannel(IOUtils.toByteArray(resourceAsStream));
             SevenZFile f = new SevenZFile(channel)) {
            Stream<Geojson.Feature> featureStream = StreamSupport.stream(f.getEntries().spliterator(), false).map(n -> {
                try {
                    SevenZArchiveEntry nextEntry = f.getNextEntry();
                    if (nextEntry != null) {
                        log.debug("Processing archive entry {}", nextEntry.getName());
                        byte[] e = new byte[(int) nextEntry.getSize()];
                        f.read(e);
                        return Geojson.Feature.parseFrom(e);
                    } else {
                        throw new RuntimeException("Data entry is not found in 7z file");
                    }
                } catch (NullPointerException | IOException ex) {
                    throw new RuntimeException(ex);
                }
            });
            Envelope2D boundaries = new Envelope2D(minLon, minLat, maxLon, maxLat);
            return new TimeZoneEngine(
                    Index.build(
                            featureStream,
                            (int) f.getEntries().spliterator().getExactSizeIfKnown(),
                            boundaries));
        } catch (NullPointerException | IOException e) {
            log.error("Unable to read resource file", e);
            throw new RuntimeException(e);
        }
    }
}

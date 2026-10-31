package com.siberanka.vibecore.hologram;

import com.siberanka.vibecore.core.validation.Problem;
import com.siberanka.vibecore.core.validation.Result;
import com.siberanka.vibecore.project.Identifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Where designs are placed. Placement ids ({@code h1}, {@code h2}, …) are
 * never reused, so a removed placement can never be confused with a new one.
 * Stored in a deterministic text format.
 */
public final class HologramPlacements {

    private static final String HEADER = "vibecore-holograms 1";
    public static final int MAX_PLACEMENTS = 2048;

    /** One placement of a design. */
    public record Placement(String id, Identifier design, UUID world, double x, double y, double z) {
        public Placement {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(design, "design");
            Objects.requireNonNull(world, "world");
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
                throw new IllegalArgumentException("coordinates must be finite");
            }
        }

        public double distanceSquared(double otherX, double otherY, double otherZ) {
            double dx = x - otherX;
            double dy = y - otherY;
            double dz = z - otherZ;
            return dx * dx + dy * dy + dz * dz;
        }
    }

    private final Map<String, Placement> placements = new TreeMap<>(HologramPlacements::compareIds);
    private long next = 1;

    private static int compareIds(String a, String b) {
        return Long.compare(Long.parseLong(a.substring(1)), Long.parseLong(b.substring(1)));
    }

    public Collection<Placement> all() {
        return List.copyOf(placements.values());
    }

    public Optional<Placement> get(String id) {
        return Optional.ofNullable(placements.get(id.toLowerCase(Locale.ROOT)));
    }

    public Result<Placement> add(Identifier design, UUID world, double x, double y, double z) {
        if (placements.size() >= MAX_PLACEMENTS) {
            return Result.rejected("placements", "limit", "at most " + MAX_PLACEMENTS + " placements");
        }
        Placement placement = new Placement("h" + next++, design, world, x, y, z);
        placements.put(placement.id(), placement);
        return Result.accepted(placement);
    }

    public Optional<Placement> move(String id, UUID world, double x, double y, double z) {
        return get(id).map(old -> {
            Placement moved = new Placement(old.id(), old.design(), world, x, y, z);
            placements.put(moved.id(), moved);
            return moved;
        });
    }

    public boolean remove(String id) {
        return placements.remove(id.toLowerCase(Locale.ROOT)) != null;
    }

    public Optional<Placement> nearest(UUID world, double x, double y, double z) {
        return placements.values().stream().filter(placement -> placement.world().equals(world))
                .min((a, b) -> Double.compare(a.distanceSquared(x, y, z), b.distanceSquared(x, y, z)));
    }

    public String serialize() {
        StringBuilder out = new StringBuilder(HEADER).append('\n').append("next ").append(next).append('\n');
        for (Placement placement : placements.values()) {
            out.append(String.format(Locale.ROOT, "placement %s %s %s %s %s %s", placement.id(),
                    placement.design(), placement.world(), placement.x(), placement.y(), placement.z())).append('\n');
        }
        return out.toString();
    }

    public static Result<HologramPlacements> deserialize(String text) {
        String[] lines = text.split("\n", -1);
        if (lines.length < 2 || !lines[0].trim().equals(HEADER)) {
            return Result.rejected("header", "format", "not a VibeCore hologram file of a supported version");
        }
        HologramPlacements loaded = new HologramPlacements();
        List<Problem> problems = new ArrayList<>();
        for (int index = 1; index < lines.length; index++) {
            String line = lines[index].trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] parts = line.split(" ");
            String location = "line " + (index + 1);
            try {
                if (parts[0].equals("next") && parts.length == 2) {
                    loaded.next = Long.parseLong(parts[1]);
                } else if (parts[0].equals("placement") && parts.length == 7 && parts[1].matches("h[0-9]{1,18}")) {
                    Placement placement = new Placement(parts[1], Identifier.parse("design", parts[2]).orThrow(),
                            UUID.fromString(parts[3]), Double.parseDouble(parts[4]), Double.parseDouble(parts[5]),
                            Double.parseDouble(parts[6]));
                    if (loaded.placements.putIfAbsent(placement.id(), placement) != null) {
                        problems.add(new Problem(location, "duplicate", placement.id() + " appears twice"));
                    }
                } else {
                    problems.add(new Problem(location, "format", "malformed entry"));
                }
            } catch (RuntimeException malformed) {
                problems.add(new Problem(location, "format", "malformed entry"));
            }
        }
        long highest = loaded.placements.keySet().stream().mapToLong(id -> Long.parseLong(id.substring(1)))
                .max().orElse(0);
        if (loaded.next <= highest) {
            loaded.next = highest + 1;
        }
        return problems.isEmpty() ? Result.accepted(loaded) : Result.rejected(problems);
    }
}

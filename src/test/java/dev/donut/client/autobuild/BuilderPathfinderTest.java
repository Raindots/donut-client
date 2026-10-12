package dev.donut.client.autobuild;

import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import dev.donut.client.autobuild.BuilderPathfinder.Cell;

class BuilderPathfinderTest {
    @Test void walksAroundAnObstacleAndNeverCrossesUnsupportedCells() {
        Set<Cell> blocked=Set.of(new Cell(2,1,-1),new Cell(2,1,0),new Cell(2,1,1));
        var terrain=new BuilderPathfinder.Terrain() {
            public boolean standable(Cell p) { return p.y()==1 && !blocked.contains(p) && p.z()!=-2; }
            public boolean headroom(Cell p) { return true; }
        };
        Cell end=new Cell(5,1,0);
        var path=BuilderPathfinder.find(new Cell(0,1,0),end,terrain,end::equals,500,10);
        assertFalse(path.isEmpty()); assertEquals(end,path.getLast());
        assertTrue(path.stream().allMatch(terrain::standable));
        assertTrue(path.stream().anyMatch(p->p.z()==2));
    }
    @Test void refusesAJumpWithABlockedDepartureCeiling() {
        var terrain=new BuilderPathfinder.Terrain() {
            public boolean standable(Cell p) { return p.equals(new Cell(0,1,0)) || p.equals(new Cell(1,2,0)); }
            public boolean headroom(Cell p) { return !p.equals(new Cell(0,2,0)); }
        };
        Cell end=new Cell(1,2,0);
        assertTrue(BuilderPathfinder.find(new Cell(0,1,0),end,terrain,end::equals,40,5).isEmpty());
    }
    @Test void unreachableGoalHonorsTheSearchBounds() {
        var terrain=new BuilderPathfinder.Terrain() {
            public boolean standable(Cell p) { return p.y()==1; }
            public boolean headroom(Cell p) { return true; }
        };
        Cell end=new Cell(1000,1,0);
        assertTrue(BuilderPathfinder.find(new Cell(0,1,0),end,terrain,end::equals,200,8).isEmpty());
    }
}

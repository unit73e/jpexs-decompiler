/*
 *  Copyright (C) 2010-2018 JPEXS, All rights reserved.
 * 
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3.0 of the License, or (at your option) any later version.
 * 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library.
 */
package com.jpexs.decompiler.graph;

import com.jpexs.decompiler.flash.BaseLocalData;
import com.jpexs.decompiler.flash.FinalProcessLocalData;
import com.jpexs.decompiler.flash.action.Action;
import com.jpexs.decompiler.flash.action.model.FunctionActionItem;
import com.jpexs.decompiler.flash.action.swf4.ActionIf;
import com.jpexs.decompiler.flash.action.swf5.ActionDefineFunction;
import com.jpexs.decompiler.flash.action.swf6.ActionStrictEquals;
import com.jpexs.decompiler.flash.action.swf7.ActionDefineFunction2;
import com.jpexs.decompiler.flash.helpers.GraphTextWriter;
import com.jpexs.decompiler.graph.model.AndItem;
import com.jpexs.decompiler.graph.model.BranchStackResistant;
import com.jpexs.decompiler.graph.model.BreakItem;
import com.jpexs.decompiler.graph.model.CommaExpressionItem;
import com.jpexs.decompiler.graph.model.ContinueItem;
import com.jpexs.decompiler.graph.model.DefaultItem;
import com.jpexs.decompiler.graph.model.DoWhileItem;
import com.jpexs.decompiler.graph.model.DuplicateItem;
import com.jpexs.decompiler.graph.model.ExitItem;
import com.jpexs.decompiler.graph.model.FalseItem;
import com.jpexs.decompiler.graph.model.ForItem;
import com.jpexs.decompiler.graph.model.GotoItem;
import com.jpexs.decompiler.graph.model.IfItem;
import com.jpexs.decompiler.graph.model.IntegerValueItem;
import com.jpexs.decompiler.graph.model.IntegerValueTypeItem;
import com.jpexs.decompiler.graph.model.LabelItem;
import com.jpexs.decompiler.graph.model.LocalData;
import com.jpexs.decompiler.graph.model.LogicalOpItem;
import com.jpexs.decompiler.graph.model.LoopItem;
import com.jpexs.decompiler.graph.model.NotItem;
import com.jpexs.decompiler.graph.model.OrItem;
import com.jpexs.decompiler.graph.model.PopItem;
import com.jpexs.decompiler.graph.model.PushItem;
import com.jpexs.decompiler.graph.model.ScriptEndItem;
import com.jpexs.decompiler.graph.model.SwitchItem;
import com.jpexs.decompiler.graph.model.TernarOpItem;
import com.jpexs.decompiler.graph.model.TrueItem;
import com.jpexs.decompiler.graph.model.UniversalLoopItem;
import com.jpexs.decompiler.graph.model.WhileItem;
import com.jpexs.helpers.Reference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Stack;
import java.util.logging.Logger;

/**
 *
 * @author JPEXS
 */
public class Graph {

    public List<GraphPart> heads;

    protected GraphSource code;

    private final List<GraphException> exceptions;

    public static final int SOP_USE_STATIC = 0;

    public static final int SOP_SKIP_STATIC = 1;

    public static final int SOP_REMOVE_STATIC = 2;

    private boolean debugPrintAllParts = false;
    private boolean debugPrintLoopList = false;
    private boolean debugGetLoops = false;
    private boolean debugPrintGraph = false;

    private static Logger logger = Logger.getLogger(Graph.class.getName());

    public GraphSource getGraphCode() {
        return code;
    }

    public LinkedHashMap<String, Graph> getSubGraphs() {
        return new LinkedHashMap<>();
    }

    protected List<GraphTargetItem> filter(List<GraphTargetItem> list) {
        return new ArrayList<>(list);
    }

    public Graph(GraphSource code, List<GraphException> exceptions) {
        this.code = code;
        this.exceptions = exceptions;

    }

    public void init(BaseLocalData localData) throws InterruptedException {
        if (heads != null) {
            return;
        }
        heads = makeGraph(code, new ArrayList<>(), exceptions);
        int time = 1;
        List<GraphPart> ordered = new ArrayList<>();
        List<GraphPart> visited = new ArrayList<>();
        for (GraphPart head : heads) {
            time = head.setTime(time, ordered, visited);
        }
    }

    public List<GraphException> getExceptions() {
        return exceptions;
    }

    protected static void populateParts(GraphPart part, Set<GraphPart> allParts) {
        if (allParts.contains(part)) {
            return;
        }
        allParts.add(part);
        for (GraphPart p : part.nextParts) {
            populateParts(p, allParts);
        }
    }

    public GraphPart deepCopy(GraphPart part) {
        return deepCopy(part, new HashMap<>());
    }

    private GraphPart deepCopy(GraphPart part, Map<GraphPart, GraphPart> copies) {
        GraphPart copy = copies.get(part);
        if (copy != null) {
            return copy;
        }

        copy = new GraphPart(part.start, part.end);
        copy.path = part.path;
        copies.put(part, copy);
        copy.nextParts = new ArrayList<>();
        for (int i = 0; i < part.nextParts.size(); i++) {
            copy.nextParts.add(deepCopy(part.nextParts.get(i), copies));
        }

        for (int i = 0; i < part.refs.size(); i++) {
            copy.refs.add(deepCopy(part.refs.get(i), copies));
        }

        return copy;
    }

    public void resetGraph(GraphPart part, Set<GraphPart> visited) {
        if (visited.contains(part)) {
            return;
        }

        visited.add(part);
        int pos = 0;
        for (GraphPart p : part.nextParts) {
            if (!visited.contains(p)) {
                p.path = part.path.sub(pos, p.end);
            }

            resetGraph(p, visited);
            pos++;
        }
    }

    private void getReachableParts(GraphPart part, LinkedHashSet<GraphPart> ret, List<Loop> loops, List<GraphPartEdge> gotoParts) {
        // use LinkedHashSet to preserve order
        getReachableParts(part, ret, loops, true, gotoParts);
    }

    private void getReachableParts(GraphPart part, LinkedHashSet<GraphPart> ret, List<Loop> loops, boolean first, List<GraphPartEdge> gotoParts) {
        // todo: honfika: why call with first = true parameter always?
        Stack<GraphPartQueue> stack = new Stack<>();
        GraphPartQueue queue = new GraphPartQueue();
        queue.add(part);
        stack.add(queue);
        stacknext:
        while (!stack.isEmpty()) {

            queue = stack.peek();
            if (!queue.isEmpty()) {
                part = queue.remove();
            } else if (queue.currentLoop != null) {
                Loop cLoop = queue.currentLoop;
                part = cLoop.loopBreak;
                queue.currentLoop = null;
                if (ret.contains(part)) {
                    continue;
                }

                ret.add(part);
                cLoop.reachableMark = 2;
            } else {
                stack.pop();
                continue;
            }

            for (Loop l : loops) {
                l.reachableMark = 0;
            }

            Loop currentLoop = null;
            for (Loop l : loops) {
                if ((l.phase == 1) || (l.reachableMark == 1)) {
                    if (l.loopContinue == part) {
                        continue stacknext;
                    }
                    if (l.loopBreak == part) {
                        continue stacknext;
                    }
                    if (l.loopPreContinue == part) {
                        continue stacknext;
                    }
                }
                if (l.reachableMark == 0) {
                    if (l.loopContinue == part) {
                        l.reachableMark = 1;
                        currentLoop = l;
                    }
                }
            }

            GraphPartQueue newParts = new GraphPartQueue();
            loopnext:
            for (GraphPart next : part.nextParts) {
                if (gotoParts.contains(new GraphPartEdge(part, next))) {
                    continue;
                }
                for (Loop l : loops) {
                    if ((l.phase == 1) || (l.reachableMark == 1)) {
                        if (l.loopContinue == next) {
                            continue loopnext;
                        }
                        if (l.loopBreak == next) {
                            continue loopnext;
                        }
                        if (l.loopPreContinue == next) {
                            continue loopnext;
                        }
                    }

                }
                if (!ret.contains(next)) {
                    newParts.add(next);
                }
            }

            ret.addAll(newParts);
            if (currentLoop != null && currentLoop.loopBreak != null) {
                newParts.currentLoop = currentLoop;
            }

            if (!newParts.isEmpty() || newParts.currentLoop != null) {
                stack.add(newParts);
            }
        }
    }

    public GraphPart getNextCommonPart(BaseLocalData localData, GraphPart part, List<Loop> loops, List<GraphPartEdge> gotoParts) throws InterruptedException {
        return getCommonPart(localData, part, part.nextParts, loops, gotoParts);
    }

    //TODO: Make this faster!
    public GraphPart getCommonPart(BaseLocalData localData, GraphPart prev, List<GraphPart> parts, List<Loop> loops, List<GraphPartEdge> gotoParts) throws InterruptedException {
        if (parts.isEmpty()) {
            return null;
        }

        List<GraphPart> loopContinues = new ArrayList<>();//getLoopsContinues(loops);
        for (Loop l : loops) {
            if (l.phase == 1) {
                loopContinues.add(l.loopContinue);
            }
        }

        for (GraphPart p : parts) {
            if (loopContinues.contains(p)) {
                break;
            }
            if (gotoParts.contains(new GraphPartEdge(prev, p))) {
                break;
            }
            boolean common = true;
            for (GraphPart q : parts) {
                if (q == p) {
                    continue;
                }
                if (!q.leadsTo(localData, this, code, p, loops, gotoParts)) {
                    common = false;
                    break;
                }
            }
            if (common) {
                return p;
            }
        }
        List<Set<GraphPart>> reachable = new ArrayList<>();
        for (GraphPart p : parts) {
            LinkedHashSet<GraphPart> r1 = new LinkedHashSet<>();
            getReachableParts(p, r1, loops, gotoParts);
            r1.add(p);
            reachable.add(r1);
        }
        Set<GraphPart> first = reachable.get(0);
        for (GraphPart p : first) {
            /*if (ignored.contains(p)) {
             continue;
             }*/
            p = checkPart(null, localData, p, null);
            if (p == null) {
                continue;
            }
            boolean common = true;
            for (Set<GraphPart> r : reachable) {
                if (!r.contains(p)) {
                    common = false;
                    break;
                }
            }
            if (common) {
                return p;
            }
        }
        return null;
    }

    public GraphPart getMostCommonPart(BaseLocalData localData, List<GraphPart> parts, List<Loop> loops, List<GraphPartEdge> gotoParts) throws InterruptedException {
        if (parts.isEmpty()) {
            return null;
        }

        Set<GraphPart> s = new HashSet<>(parts); //unique
        parts = new ArrayList<>(s); //make local copy

        List<GraphPart> loopContinues = new ArrayList<>();//getLoopsContinues(loops);
        for (Loop l : loops) {
            if (l.phase == 1) {
                loopContinues.add(l.loopContinue);
                loopContinues.add(l.loopPreContinue);
            }
        }

        for (GraphPart p : parts) {
            if (loopContinues.contains(p)) {
                break;
            }
            boolean common = true;
            for (GraphPart q : parts) {
                if (q == p) {
                    continue;
                }
                if (!q.leadsTo(localData, this, code, p, loops, gotoParts)) {
                    common = false;
                    break;
                }
            }
            if (common) {
                return p;
            }
        }

        loopi:
        for (int i = 0; i < parts.size(); i++) {
            for (int j = 0; j < parts.size(); j++) {
                if (j == i) {
                    continue;
                }
                if (parts.get(i).leadsTo(localData, this, code, parts.get(j), loops, gotoParts)) {
                    parts.remove(i);
                    i--;
                    continue loopi;
                }
            }
        }
        List<Set<GraphPart>> reachable = new ArrayList<>();
        for (GraphPart p : parts) {
            LinkedHashSet<GraphPart> r1 = new LinkedHashSet<>();
            getReachableParts(p, r1, loops, gotoParts);
            Set<GraphPart> r2 = new LinkedHashSet<>();
            r2.add(p);
            r2.addAll(r1);
            reachable.add(r2);
        }
        ///List<GraphPart> first = reachable.get(0);
        int commonLevel;
        Map<GraphPart, Integer> levelMap = new HashMap<>();
        for (Set<GraphPart> first : reachable) {
            int maxclevel = 0;
            Set<GraphPart> visited = new HashSet<>();
            for (GraphPart p : first) {
                if (loopContinues.contains(p)) {
                    break;
                }
                if (visited.contains(p)) {
                    continue;
                }
                visited.add(p);
                boolean common = true;
                commonLevel = 1;
                for (Set<GraphPart> r : reachable) {
                    if (r == first) {
                        continue;
                    }
                    if (r.contains(p)) {
                        commonLevel++;
                    }
                }
                if (commonLevel <= maxclevel) {
                    continue;
                }
                maxclevel = commonLevel;
                if (levelMap.containsKey(p)) {
                    if (levelMap.get(p) > commonLevel) {
                        commonLevel = levelMap.get(p);
                    }
                }
                levelMap.put(p, commonLevel);
                if (common) {
                    //return p;
                }
            }
        }
        for (int i = reachable.size() - 1; i >= 2; i--) {
            for (GraphPart p : levelMap.keySet()) {
                if (levelMap.get(p) == i) {
                    return p;
                }
            }
        }
        for (GraphPart p : levelMap.keySet()) {
            if (levelMap.get(p) == parts.size()) {
                return p;
            }
        }
        return null;
    }

    public GraphPart getNextNoJump(GraphPart part, BaseLocalData localData) {
        while (code.get(part.start).isJump()) {
            part = part.getSubParts().get(0).nextParts.get(0);
        }
        /*localData = prepareBranchLocalData(localData);
         TranslateStack st = new TranslateStack();
         List<GraphTargetItem> output=new ArrayList<>();
         GraphPart startPart = part;
         for (int i = part.start; i <= part.end; i++) {
         GraphSourceItem src = code.get(i);
         if (src.isJump()) {
         part = part.nextParts.get(0);
         if(st.isEmpty()){
         startPart = part;
         }
         i = part.start - 1;
         continue;
         }
         try{
         src.translate(localData, st, output, SOP_USE_STATIC, "");
         }catch(Exception ex){
         return startPart;
         }
         if(!output.isEmpty()){
         return startPart;
         }
         }*/
        return part;
    }

    public static List<GraphTargetItem> translateViaGraph(BaseLocalData localData, String path, GraphSource code, List<GraphException> exceptions, int staticOperation) throws InterruptedException {
        Graph g = new Graph(code, exceptions);
        g.init(localData);
        return g.translate(localData, staticOperation, path);
    }

    protected void afterPopupateAllParts(Set<GraphPart> allParts) {

    }

    public List<GraphTargetItem> translate(BaseLocalData localData, int staticOperation, String path) throws InterruptedException {

        Set<GraphPart> allParts = new HashSet<>();
        for (GraphPart head : heads) {
            populateParts(head, allParts);
        }
        afterPopupateAllParts(allParts);
        if (debugPrintAllParts) {
            System.err.println("parts:");
            for (GraphPart p : allParts) {
                System.err.print(p);
                if (!p.nextParts.isEmpty()) {
                    System.err.print(", next: ");
                }
                boolean first = true;
                for (GraphPart n : p.nextParts) {
                    if (!first) {
                        System.err.print(",");
                    }
                    System.err.print(n);
                    first = false;
                }
                System.err.println("");
            }
            System.err.println("/parts");
        }
        TranslateStack stack = new TranslateStack(path);
        List<Loop> loops = new ArrayList<>();

        getLoops(localData, heads.get(0), loops, null);

        if (debugPrintLoopList) {
            System.err.println("<loops>");
            for (Loop el : loops) {
                System.err.println(el);
            }
            System.err.println("</loops>");
        }

        //TODO: Make getPrecontinues faster
        getBackEdges(localData, loops, new ArrayList<>());
        //getPrecontinues(path, localData, null, heads.get(0), allParts, loops, null);
        //getPrecontinues2(path, localData, null, heads.get(0), allParts, loops, null);
        List<GraphPartEdge> gotoTargets = new ArrayList<>();

        findGotoTargets(localData, path, heads.get(0), allParts, loops, gotoTargets);

        /*System.err.println("<loopspre>");
         for (Loop el : loops) {
         System.err.println(el);
         }
         System.err.println("</loopspre>");//*/
        List<GotoItem> gotos = new ArrayList<>();

        beforePrintGraph(localData, path, allParts, loops);
        List<GraphTargetItem> ret = printGraph(gotos, gotoTargets, new HashMap<>(), new HashMap<>(), localData, stack, allParts, null, heads.get(0), null, loops, staticOperation, path);

        processIfGotos(gotos, ret);

        Map<String, Integer> usages = new HashMap<>();
        Map<String, GotoItem> lastUsage = new HashMap<>();
        for (GotoItem gi : gotos) {
            if (!usages.containsKey(gi.labelName)) {
                usages.put(gi.labelName, 0);
            }
            usages.put(gi.labelName, usages.get(gi.labelName) + 1);
            lastUsage.put(gi.labelName, gi);
        }
        for (String labelName : usages.keySet()) {
            logger.fine("usage - " + labelName + ": " + usages.get(labelName));
            if (usages.get(labelName) == 1) {
                lastUsage.get(labelName).labelName = null;
            }
        }

        expandGotos(ret);
        processIfs(ret);
        finalProcessStack(stack, ret, path);
        finalProcessAll(ret, 0, new FinalProcessLocalData(loops), path);
        return ret;
    }

    protected void beforePrintGraph(BaseLocalData localData, String path, Set<GraphPart> allParts, List<Loop> loops) {

    }

    private List<GraphPartDecision> getCommonPrefix(List<List<GraphPartDecision>> listOfLists) {
        List<GraphPartDecision> result = new ArrayList<>();
        if (listOfLists.isEmpty()) {
            return result;
        }

        int maxlen = Integer.MAX_VALUE;
        for (int j = 0; j < listOfLists.size(); j++) {
            if (listOfLists.get(j).size() < maxlen) {
                maxlen = listOfLists.get(j).size();
            }
        }
        for (int i = 0; i < maxlen; i++) {
            List<GraphPartDecision> firstList = listOfLists.get(0);
            for (int j = 1; j < listOfLists.size(); j++) {
                if (!listOfLists.get(j).get(i).equals(firstList.get(i))) {
                    return result;
                }
            }
            result.add(firstList.get(i));
        }
        return result;
    }

    private boolean isDecisionJoin(List<GraphPartDecision> list1, List<GraphPartDecision> list2) {
        if (list1.size() != list2.size()) {
            return false;
        }
        if (list1.isEmpty()) {
            return false;
        }
        for (int i = 0; i < list1.size() - 1; i++) {
            if (!list1.get(i).equals(list2.get(i))) {
                return false;
            }
        }
        if (!list1.get(list1.size() - 1).part.equals(list2.get(list2.size() - 1).part)) {
            return false;
        }
        if (list1.get(list1.size() - 1).way == list2.get(list2.size() - 1).way) {
            return false;
        }
        return true;
    }

    private void findGotoTargetsWalk(Set<GraphPartEdge> ignoredBreakEdges, Map<GraphPart, GraphPart> partReplacements, Map<GraphPart, List<GraphPart>> partToNext, Map<GraphPart, List<GraphPart>> partToPrev, Reference<Integer> currentVirtualNum, List<GraphExceptionParts> openedExceptions, GraphPartEdge e,
            Set<GraphPart> opened,
            Set<GraphPart> closed,
            Set<GraphPart> closedBranches,
            Set<GraphPartEdge> exitEdges,
            Set<GraphPartEdge> backEdges,
            Set<GraphPartEdge> throwEdges,
            Set<GraphPartEdge> allowedThrowEdges,
            List<GraphExceptionParts> exceptionParts,
            Map<GraphPartEdge, List<GraphPartDecision>> edgeToBranches,
            GraphPart start,
            List<GraphPartEdge> gotoTargets) {
        GraphPart p = e.to;
        if (closed.contains(p)) {
            logger.fine("part " + p + " is already closed, skipping");
            return;
        }
        logger.fine("processing " + p);
        if (!edgeToBranches.containsKey(e)) {
            edgeToBranches.put(e, new ArrayList<>());
        }
        List<GraphPartDecision> branches = edgeToBranches.get(e);
        if (p != start) {
            List<GraphPart> refs = getUnicatePartList(partToPrev.get(p));

            List<List<GraphPartDecision>> comparedPaths = new ArrayList<>();
            List<GraphPartEdge> comparedPathsEdges = new ArrayList<>();
            for (GraphPart r : refs) {
                GraphPartEdge re = new GraphPartEdge(r, p);
                /*if (throwEdges.contains(re) && !allowedThrowEdges.contains(re)) {
                    logger.fine("ref edge " + re + " is throwedge, ignored");
                    continue;
                }*/
                if (backEdges.contains(re)) {
                    logger.fine("ref edge " + re + " is backedge, ignored");
                    continue;
                }
                if (r.start == -1) {
                    logger.fine("ref edge " + re + " is alternatestart, ignored");
                    continue;
                }
                if (!edgeToBranches.containsKey(re)) {
                    //edge not yet processed
                    logger.fine("ref edge " + re + " NOT yet processed");
                    return;
                } else {
                    logger.fine("ref edge " + re + " already processed");
                }
                comparedPaths.add(edgeToBranches.get(re));
                comparedPathsEdges.add(re);

            }

            logger.fine("COMPARED PATHS:");
            for (int i = 0; i < comparedPaths.size(); i++) {
                logger.fine("- " + (System.identityHashCode(comparedPaths.get(i))) + ": " + pathToString(comparedPaths.get(i)));
            }
            logger.fine("curren path: " + System.identityHashCode(branches));

            boolean isEndOfBlock = false;

            Set<GraphPart> partsToClose = new HashSet<>();

            //Map<GraphPart, Integer> decisionToRemovedCount = new HashMap<>();
            loopi:
            for (int i = 0; i < comparedPaths.size(); i++) {
                for (int j = 0; j < comparedPaths.size(); j++) {
                    if (i == j) {
                        continue;
                    }
                    if (isDecisionJoin(comparedPaths.get(i), comparedPaths.get(j))) {
                        if (comparedPaths.get(i).isEmpty()) {
                            comparedPaths.remove(i);
                            comparedPathsEdges.remove(i);
                            i--;
                            continue loopi;
                        }
                        logger.fine("merged paths:" + pathToString(comparedPaths.get(i)) + " of edge " + comparedPathsEdges.get(i));

                        GraphPartDecision decision = comparedPaths.get(i).get(comparedPaths.get(i).size() - 1);
                        if (partToNext.get(decision.part).size() > 2) {
                            int removedCount = 0;
                            for (int k = comparedPaths.size() - 1; k >= 0; k--) {
                                if (k == i) {
                                    continue;
                                }
                                if (isDecisionJoin(comparedPaths.get(i), comparedPaths.get(k))) {
                                    comparedPaths.remove(k);
                                    comparedPathsEdges.remove(k);
                                    removedCount++;
                                }
                                if (removedCount == partToNext.get(decision.part).size() - 1) {
                                    break;
                                }
                            }
                            //decisionToRemovedCount.put(decision, removedCount);
                            comparedPaths.get(i).remove(comparedPaths.get(i).size() - 1);
                        } else {
                            //remove last path component
                            int last = i > j ? i : j;
                            int first = i < j ? i : j;
                            comparedPaths.get(i).remove(comparedPaths.get(i).size() - 1);
                            comparedPaths.get(j).remove(comparedPaths.get(j).size() - 1);
                            comparedPaths.remove(last);
                            comparedPathsEdges.remove(last);
                        }
                        //logger.fine("normal closing " + decision);

                        if (!closedBranches.contains(decision.part)) {
                            logger.fine("on part " + p);
                            logger.fine("normal closing branch " + decision);
                            partsToClose.add(decision.part);
                        } else {
                            logger.fine("branch already closed: " + decision);
                            isEndOfBlock = true;
                        }
                        partsToClose.add(decision.part);
                        i = -1;
                        continue loopi;
                    }
                }
            }
            for (List<GraphPartDecision> cp : comparedPaths) {
                logger.fine("- branches: " + System.identityHashCode(cp) + ": " + pathToString(cp));
            }
            logger.fine("current branches: " + System.identityHashCode(branches) + ": " + pathToString(branches));

            List<GraphPartEdge> foundBreakEdges = new ArrayList<>();
            if (comparedPaths.size() > 1) {
                logger.fine("not a single path - paths left: " + comparedPaths.size());
                List<GraphPartDecision> prefix = getCommonPrefix(comparedPaths);

                //GraphPartDecision decision = prefix.isEmpty() ? null : prefix.get(prefix.size() - 1);
                //int removedCount = decisionToRemovedCount.containsKey(decision) ? decisionToRemovedCount.get(decision) : 0;
                for (int i = 0; i < comparedPaths.size(); i++) {
                    for (int j = prefix.size(); j < comparedPaths.get(i).size(); j++) {
                        GraphPart partToClose = comparedPaths.get(i).get(j).part;
                        GraphPartEdge edgeToClose = comparedPathsEdges.get(i);
                        if (!closedBranches.contains(partToClose)) {
                            logger.fine("on part " + p);
                            logger.fine("closing branch " + partToClose);
                            partsToClose.add(partToClose);
                        } else {
                            logger.fine("branch already closed: " + partToClose);
                            logger.fine("probably break edge: " + edgeToClose);
                            if (ignoredBreakEdges.contains(edgeToClose)) {
                                foundBreakEdges.add(edgeToClose);
                                logger.fine("NOT a break edge, it is standard break");
                                continue;
                            }
                            isEndOfBlock = true;
                        }
                    }
                }

                /*if (decision != null) {
                    logger.fine("closing branch 2: " + decision);
                    closedBranches.add(decision);
                }*/
                branches = prefix;
                if (!branches.isEmpty()) {
                    //logger.fine("removedCount before: " + removedCount);
                    //removedCount += comparedPaths.size();
                    /*if (partToNext.get(decision).size() > 2 && removedCount < partToNext.get(decision).size() - 1) {
                        //ignore
                    } else {*/
 /*branches.remove(branches.size() - 1);
                        logger.fine("closing branch 2: " + decision);
                        closedBranches.add(decision);*/
                    //}
                }
            } else if (!comparedPaths.isEmpty()) {
                branches = comparedPaths.get(0);
            }
            closedBranches.addAll(partsToClose);

            if (isEndOfBlock) {
                logger.fine("found breaks to " + p);
                for (GraphPart r : p.refs) {
                    gotoTargets.add(new GraphPartEdge(r, p));
                }
                /*for (GraphPartEdge be : foundBreakEdges) {
                    GraphPartEdge re = findGotoRestoreOrigEdge(be);
                    gotoTargets.add(re);
                }*/
            }
        }

        GraphExceptionParts nearestEx = null;
        List<GraphExceptionParts> currentExceptions = new ArrayList<>();
        List<GraphPart> currentExceptionTargets = new ArrayList<>();

        for (GraphExceptionParts ex : exceptionParts) {
            if (openedExceptions.contains(ex)) {
                continue;
            }
            if (p.equals(ex.start)) {
                if (nearestEx != null && !ex.end.equals(nearestEx.end)) {
                    continue;
                }
                currentExceptions.add(ex);
                nearestEx = ex;
                currentExceptionTargets.add(ex.target);
            }
        }

        if (!currentExceptionTargets.isEmpty()) {
            openedExceptions.addAll(currentExceptions);
            List<GraphPart> virtualNexts = new ArrayList<>();
            virtualNexts.add(p);
            virtualNexts.addAll(currentExceptionTargets);
            GraphPart end = nearestEx.end;
            if (partReplacements.containsKey(end)) {
                end = partReplacements.get(end);
            }
            virtualNexts.add(end);
            currentVirtualNum.setVal(currentVirtualNum.getVal() - 1);
            GraphPart virtualPart = new GraphPart(currentVirtualNum.getVal(), currentVirtualNum.getVal());
            partToNext.put(virtualPart, virtualNexts);
            partToPrev.put(virtualPart, new ArrayList<>());

            // connection from prevs of p to virtualPart
            for (int k = 0; k < partToPrev.get(p).size(); k++) {
                GraphPart pr = partToPrev.get(p).get(k);
                List<GraphPart> prevNexts = partToNext.get(pr);
                for (int j = 0; j < prevNexts.size(); j++) {
                    if (prevNexts.get(j).equals(p)) {
                        prevNexts.set(j, virtualPart);
                    }
                }
                partToPrev.get(virtualPart).add(pr);
            }
            for (GraphPart t : virtualNexts) {
                if (t == end) {
                    partToPrev.get(t).add(virtualPart);
                } else {
                    partToPrev.get(t).clear();
                    partToPrev.get(t).add(virtualPart);
                }
            }
            findGotoWalkNexts(ignoredBreakEdges, partReplacements, partToNext, partToPrev, currentVirtualNum, openedExceptions, opened, closed, closedBranches, exitEdges, backEdges, throwEdges, allowedThrowEdges, exceptionParts, edgeToBranches, start, gotoTargets, virtualPart, branches);
        } else {
            findGotoWalkNexts(ignoredBreakEdges, partReplacements, partToNext, partToPrev, currentVirtualNum, openedExceptions, opened, closed, closedBranches, exitEdges, backEdges, throwEdges, allowedThrowEdges, exceptionParts, edgeToBranches, start, gotoTargets, p, branches);
        }
    }

    /*private GraphPartEdge findGotoRestoreOrigEdge(GraphPartEdge e) {
        for (GraphPart r : e.to.refs) {
            if (r.equals(e.from)) {
                return e;
            }
        }
        for (GraphPart r : e.to.refs) {
            GraphPart rr = r;
            while (isPartEmpty(rr) && rr.refs.size() == 1) {
                rr = rr.refs.get(0);
            }
            if (rr.equals(e.from)) {
                return new GraphPartEdge(r, e.to);
            }
        }
        return null;
    }*/
    protected boolean isPartEmpty(GraphPart part) {
        return false;
    }

    private void findGotoWalkNexts(Set<GraphPartEdge> ignoredBreakEdges, Map<GraphPart, GraphPart> partReplacements, Map<GraphPart, List<GraphPart>> partToNext, Map<GraphPart, List<GraphPart>> partToPrev, Reference<Integer> currentVirtualNum, List<GraphExceptionParts> openedExceptions,
            Set<GraphPart> opened,
            Set<GraphPart> closed,
            Set<GraphPart> closedBranches,
            Set<GraphPartEdge> exitEdges,
            Set<GraphPartEdge> backEdges,
            Set<GraphPartEdge> throwEdges,
            Set<GraphPartEdge> allowedThrowEdges,
            List<GraphExceptionParts> exceptionParts,
            Map<GraphPartEdge, List<GraphPartDecision>> edgeToBranches,
            GraphPart start,
            List<GraphPartEdge> gotoTargets,
            GraphPart p,
            List<GraphPartDecision> branches) {
        List<GraphPart> nexts = new ArrayList<>();

        //filter out backedges           
        for (GraphPart n : partToNext.get(p)) {
            GraphPartEdge ne = new GraphPartEdge(p, n);
            if (!backEdges.contains(ne)) {
                nexts.add(n);
            } else {
                logger.fine("next edge " + ne + " is backedge, ignored");
            }
        }

        closed.add(p);

        Stack<GraphPartEdge> walkStack = new Stack<>();
        logger.fine("processing nextparts of " + p);
        for (int i = 0; i < nexts.size(); i++) {
            GraphPart n = nexts.get(i);
            GraphPartEdge ne = new GraphPartEdge(p, n);
            List<GraphPartDecision> subBranches = branches;
            if (nexts.size() > 1) {
                subBranches = new ArrayList<>(branches);
                subBranches.add(new GraphPartDecision(p, i));
            }
            edgeToBranches.put(ne, subBranches);
            walkStack.push(ne);
        }

        while (!walkStack.isEmpty()) {
            findGotoTargetsWalk(ignoredBreakEdges, partReplacements, partToNext, partToPrev, currentVirtualNum, openedExceptions, walkStack.pop(), opened, closed, closedBranches, exitEdges, backEdges, throwEdges, allowedThrowEdges, exceptionParts, edgeToBranches, start, gotoTargets);
        }
    }

    private void findGotoTargets(BaseLocalData localData, String path, GraphPart startPart, Set<GraphPart> allParts, List<Loop> loops, List<GraphPartEdge> gotoTargets) throws InterruptedException {
        if (!path.endsWith(".run")) {
            //return;
        }
        logger.fine("------ " + path);
        //logger.info("GETTING precontinues of " + path + " =================");
        Set<GraphPart> opened = new HashSet<>();
        Set<GraphPart> closed = new HashSet<>();
        Set<GraphPart> closedBranches = new HashSet<>();
        Set<GraphPartEdge> exitEdges = new HashSet<>();
        Set<GraphPartEdge> backEdges = new HashSet<>();
        Set<GraphPartEdge> throwEdges = new HashSet<>();
        Set<GraphPartEdge> allowedThrowEdges = new HashSet<>();
        List<GraphExceptionParts> exceptionParts = new ArrayList<>();
        Map<Integer, GraphPart> partByIp = new HashMap<>();
        Map<GraphPart, List<GraphPart>> partToNext = new HashMap<>();
        Map<GraphPart, List<GraphPart>> partToPrev = new HashMap<>();
        Map<GraphPart, GraphPart> partReplacements = new HashMap<>();
        for (GraphPart p : allParts) {
            if (p.start == -1) {
                continue;
            }
            partByIp.put(p.start, p);
            partToNext.put(p, new ArrayList<>());
            partToNext.get(p).addAll(p.nextParts);
            partToPrev.put(p, new ArrayList<>());

            for (GraphPart r : p.refs) {
                if (r.start != -1) {
                    partToPrev.get(p).add(r);
                }
            }
        }

        for (GraphPart p : allParts) {
            if (p.start == -1) {
                continue;
            }
            GraphPart pr = partReplacements.containsKey(p) ? partReplacements.get(p) : p;
            if (isPartEmpty(pr) && partToNext.get(pr).size() == 1) {

                GraphPart afterP = partToNext.get(pr).get(0);
                while (partToPrev.get(afterP).contains(pr)) {
                    partToPrev.get(afterP).remove(pr);
                }

                for (int i = 0; i < partToPrev.get(pr).size(); i++) {
                    GraphPart pp = partToPrev.get(pr).get(i);
                    partToNext.get(pp).remove(pr);
                    partToNext.get(pp).add(afterP);
                    partToPrev.get(afterP).add(pp);
                }
                partReplacements.put(pr, afterP);

            }
        }

        for (Loop el : loops) {
            for (GraphPart g : el.backEdges) {
                backEdges.add(new GraphPartEdge(g, el.loopContinue));
            }
        }

        Set<GraphPartEdge> ignoredBreakEdges = new HashSet<>();

        for (Loop el : loops) {
            el.phase = 2; //?
        }
        for (Loop el : loops) {
            if (el.loopBreak != null && el.loopContinue != null) {
                GraphPart br = el.loopBreak;
                GraphPart brRepl = partReplacements.containsKey(br) ? partReplacements.get(br) : br;
                GraphPart cntRepl = partReplacements.containsKey(el.loopContinue) ? partReplacements.get(el.loopContinue) : el.loopContinue;
                for (GraphPart p : partToPrev.get(brRepl)) {
                    GraphPartEdge e = new GraphPartEdge(p, brRepl);
                    logger.fine("continue " + cntRepl + " leads to " + p + " ?");
                    if (cntRepl.equals(p) || findGotoTargetsLeadsTo(cntRepl, p, partToNext, backEdges, new HashSet<>())) {
                        ignoredBreakEdges.add(e);
                        logger.fine("YES: ingored break edge: " + e);
                    } else {
                        logger.fine("NO: NOT A BREAK EDGE: " + e);
                    }
                }
            }
        }
        clearLoops(loops);

        for (GraphException ex : exceptions) {
            exceptionParts.add(new GraphExceptionParts(
                    partByIp.containsKey(ex.start) ? partByIp.get(ex.start) : null,
                    partByIp.containsKey(ex.end) ? partByIp.get(ex.end) : null,
                    partByIp.containsKey(ex.target) ? partByIp.get(ex.target) : null
            ));
        }

        for (GraphPart p : allParts) {
            for (GraphPart tp : p.throwParts) {
                GraphPartEdge e = new GraphPartEdge(p, tp);
                if (!throwEdges.contains(e)) {
                    throwEdges.add(e);
                    logger.fine("throwpart " + e);
                    if (isTryBegin(p)) {
                        allowedThrowEdges.add(e);
                    }
                }
            }
        }

        Map<GraphPartEdge, List<GraphPartDecision>> edgeToBranches = new HashMap<>();
        opened.add(startPart);
        GraphPart start = startPart;
        findGotoTargetsWalk(ignoredBreakEdges, partReplacements, partToNext, partToPrev, new Reference<>(-2), new ArrayList<>(), new GraphPartEdge(startPart, startPart), opened, closed, closedBranches, exitEdges, backEdges, throwEdges, allowedThrowEdges, exceptionParts, edgeToBranches, start, gotoTargets);
    }

    private boolean findGotoTargetsLeadsTo(
            GraphPart part,
            GraphPart target,
            Map<GraphPart, List<GraphPart>> partToNext,
            Set<GraphPartEdge> backEdges, Set<GraphPart> visited) {
        visited.add(part);
        for (GraphPart n : partToNext.get(part)) {
            if (visited.contains(n)) {
                continue;
            }
            GraphPartEdge e = new GraphPartEdge(part, n);
            if (backEdges.contains(e)) {
                continue;
            }
            if (n.equals(target)) {
                return true;
            }
            if (findGotoTargetsLeadsTo(n, target, partToNext, backEdges, visited)) {
                return true;
            }
        }
        return false;
    }

    private boolean isTryBegin(GraphPart part) {
        for (GraphPart r : part.refs) {
            if (r.start == -1) {
                return true;
            }
        }
        return false;
    }

    private String pathToString(Collection<? extends Object> list) {
        List<String> strs = new ArrayList<>();
        for (Object p : list) {
            strs.add(p.toString());
        }
        return "[" + String.join(", ", strs) + "]";
    }

    private List<GraphPart> getUnicatePartList(List<GraphPart> list) {
        List<GraphPart> result = new ArrayList<>();
        for (GraphPart p : list) {
            if (!result.contains(p)) {
                result.add(p);
            }
        }
        return result;
    }

    private List<GraphPart> getUnicateRefsNoThrow(GraphPart part, Set<GraphPartEdge> throwEdges) {
        List<GraphPart> result = new ArrayList<>();
        for (GraphPart r : part.refs) {
            GraphPartEdge edge = new GraphPartEdge(r, part);
            if (!throwEdges.contains(edge)) {
                result.add(r);
            }
        }

        return getUnicatePartList(result);
    }

    private List<GraphPart> getUsableRefs(GraphPart g, List<Loop> loops) {
        List<GraphPart> ret = getUnicatePartList(g.refs);
        for (Loop el : loops) {
            for (GraphPart be : el.backEdges) {
                if (ret.contains(be)) {
                    ret.remove(be);
                }
            }
        }
        return ret;
    }


    /**/
    //if (ref.nextParts)
    private void getBackEdges(BaseLocalData localData, List<Loop> loops, List<GraphPartEdge> gotoParts) throws InterruptedException {
        clearLoops(loops);
        for (Loop el : loops) {
            el.backEdges.clear();
            Set<GraphPart> uniqueRefs = new HashSet<>(el.loopContinue.refs);
            for (GraphPart r : uniqueRefs) {
                if (el.loopContinue.leadsTo(localData, this, code, r, loops, gotoParts)) {
                    el.backEdges.add(r);
                }
            }
            el.phase = 1;
        }
        clearLoops(loops);
    }

    public void finalProcessStack(TranslateStack stack, List<GraphTargetItem> output, String path) {
    }

    private void finalProcessAll(List<GraphTargetItem> list, int level, FinalProcessLocalData localData, String path) throws InterruptedException {
        finalProcess(list, level, localData, path);
        for (GraphTargetItem item : list) {
            if (item instanceof Block) {
                List<List<GraphTargetItem>> subs = ((Block) item).getSubs();
                for (List<GraphTargetItem> sub : subs) {
                    finalProcessAll(sub, level + 1, localData, path);
                }
            }
        }
        finalProcessAfter(list, level, localData, path);
    }

    private boolean processSubBlk(Block b, GraphTargetItem replacement) {
        boolean allSubPush = true;
        boolean atleastOne = false;
        for (List<GraphTargetItem> sub : b.getSubs()) {
            if (!sub.isEmpty()) {
                int lastPos = sub.size() - 1;

                GraphTargetItem last = sub.get(sub.size() - 1);
                GraphTargetItem br = null;

                if ((last instanceof BreakItem) && (sub.size() >= 2)) {
                    br = last;
                    lastPos--;
                    last = sub.get(lastPos);
                }
                if (last instanceof Block) {
                    if (!processSubBlk((Block) last, replacement)) {
                        allSubPush = false;
                    } else {
                        atleastOne = true;
                    }
                } else if (last instanceof PushItem) {
                    if (replacement != null) {
                        GraphTargetItem e2 = (((GraphTargetItem) replacement).clone());
                        e2.value = last.value;
                        sub.set(lastPos, e2);
                        if (br != null) {
                            sub.remove(sub.size() - 1);
                        }
                    }
                    atleastOne = true;
                } else if (!(last instanceof ExitItem)) {
                    allSubPush = false;
                }
            }
        }
        return allSubPush && atleastOne;
    }

    protected void finalProcessAfter(List<GraphTargetItem> list, int level, FinalProcessLocalData localData, String path) {
        if (list.size() >= 2) {
            if (list.get(list.size() - 1) instanceof ExitItem) {
                ExitItem e = (ExitItem) list.get(list.size() - 1);
                if (list.get(list.size() - 1).value instanceof PopItem) {
                    if (list.get(list.size() - 2) instanceof Block) {
                        Block b = (Block) list.get(list.size() - 2);
                        if (processSubBlk(b, (GraphTargetItem) e)) {
                            list.remove(list.size() - 1);
                        }
                    }
                }
            }
        }
    }

    protected void finalProcess(List<GraphTargetItem> list, int level, FinalProcessLocalData localData, String path) throws InterruptedException {

        //For detection based on debug line information
        boolean[] toDelete = new boolean[list.size()];
        for (int i = 0; i < list.size(); i++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException();
            }

            GraphTargetItem itemI = list.get(i);
            if (itemI instanceof ForItem) {
                ForItem fori = (ForItem) itemI;
                int exprLine = fori.getLine();
                if (exprLine > 0) {
                    List<GraphTargetItem> forFirstCommands = new ArrayList<>();
                    for (int j = i - 1; j >= 0; j--) {
                        if (list.get(j).getLine() == exprLine && !(list.get(j) instanceof LoopItem /*to avoid recursion and StackOverflow*/)) {
                            forFirstCommands.add(0, list.get(j));
                            toDelete[j] = true;
                        } else {
                            break;
                        }
                    }
                    fori.firstCommands.addAll(0, forFirstCommands);
                }
            }

            if (itemI instanceof WhileItem) {
                WhileItem whi = (WhileItem) itemI;
                int whileExprLine = whi.getLine();
                if (whileExprLine > 0) {
                    List<GraphTargetItem> forFirstCommands = new ArrayList<>();
                    List<GraphTargetItem> forFinalCommands = new ArrayList<>();

                    for (int j = i - 1; j >= 0; j--) {
                        GraphTargetItem itemJ = list.get(j);
                        if (itemJ.getLine() == whileExprLine && !(itemJ instanceof LoopItem /*to avoid recursion and StackOverflow*/)) {
                            forFirstCommands.add(0, itemJ);
                            toDelete[j] = true;
                        } else {
                            break;
                        }
                    }
                    for (int j = whi.commands.size() - 1; j >= 0; j--) {
                        if (whi.commands.get(j).getLine() == whileExprLine && !(whi.commands.get(j) instanceof LoopItem /*to avoid recursion and StackOverflow*/)) {
                            forFinalCommands.add(0, whi.commands.remove(j));
                        } else {
                            break;
                        }
                    }
                    if (!forFirstCommands.isEmpty() || !forFinalCommands.isEmpty()) {
                        //Do not allow more than 2 first/final commands, since it can be obfuscated
                        if (forFirstCommands.size() > 2 || forFinalCommands.size() > 2) {
                            //put it back
                            for (int k = 0; k < forFirstCommands.size(); k++) {
                                toDelete[i - 1 - k] = false;
                            }
                            whi.commands.addAll(forFinalCommands); //put it back
                        } else if (whi.commands.isEmpty() && forFirstCommands.isEmpty()) {
                            //it would be for(;expr;commands) {}  which looks better as while(expr){commands}
                            whi.commands.addAll(forFinalCommands); //put it back
                        } else {
                            GraphTargetItem lastExpr = whi.expression.remove(whi.expression.size() - 1);
                            forFirstCommands.addAll(whi.expression);
                            list.set(i, new ForItem(whi.getSrc(), whi.getLineStartItem(), whi.loop, forFirstCommands, lastExpr, forFinalCommands, whi.commands));
                        }
                    }
                }
            }
        }

        for (int i = toDelete.length - 1; i >= 0; i--) {
            if (toDelete[i]) {
                list.remove(i);
            }
        }
    }

    private void expandGotos(List<GraphTargetItem> list) {
        if (!list.isEmpty() && (list.get(list.size() - 1) instanceof GotoItem)) {
            GotoItem gi = (GotoItem) list.get(list.size() - 1);
            if (gi.targetCommands != null) {
                list.remove(gi);
                if (gi.labelName != null) {
                    list.add(new LabelItem(null, gi.lineStartItem, gi.labelName));
                }
                list.addAll(gi.targetCommands);
            }
        }
        for (int i = 0; i < list.size(); i++) {
            GraphTargetItem item = list.get(i);
            if (item instanceof Block) {
                List<List<GraphTargetItem>> subs = ((Block) item).getSubs();
                for (List<GraphTargetItem> sub : subs) {
                    expandGotos(sub);
                }
            }
        }
    }

    /**
     * if (xxx) { y ; goto a } else { z ; goto a }
     *
     * =>
     *
     * if (xxx) { y } else { z } goto a
     *
     */
    private void processIfGotos(List<GotoItem> allGotos, List<GraphTargetItem> list) {
        for (int i = 0; i < list.size(); i++) {
            GraphTargetItem item = list.get(i);
            if (item instanceof Block) {
                List<List<GraphTargetItem>> subs = ((Block) item).getSubs();
                for (List<GraphTargetItem> sub : subs) {
                    processIfGotos(allGotos, sub);
                }
            }
            if (item instanceof IfItem) {
                IfItem ii = (IfItem) item;
                if (!ii.onTrue.isEmpty() && !ii.onFalse.isEmpty()) {
                    if (ii.onTrue.get(ii.onTrue.size() - 1) instanceof GotoItem) {
                        if (ii.onFalse.get(ii.onFalse.size() - 1) instanceof GotoItem) {
                            GotoItem gotoOnTrue = (GotoItem) ii.onTrue.get(ii.onTrue.size() - 1);
                            GotoItem gotoOnFalse = (GotoItem) ii.onFalse.get(ii.onFalse.size() - 1);
                            if (gotoOnTrue.labelName.equals(gotoOnFalse.labelName)) {
                                String labelOnTrue = gotoOnTrue.labelName;
                                String labelOnFalse = gotoOnFalse.labelName;
                                if (labelOnTrue != null && labelOnFalse != null) {
                                    if (labelOnTrue.equals(labelOnFalse)) {
                                        GotoItem gotoMerged;
                                        GotoItem gotoRemoved;
                                        if (gotoOnTrue.targetCommands != null) {
                                            gotoMerged = gotoOnTrue;
                                            gotoRemoved = gotoOnFalse;
                                        } else {
                                            gotoMerged = gotoOnFalse;
                                            gotoRemoved = gotoOnTrue;
                                        }
                                        ii.onTrue.remove(ii.onTrue.size() - 1);
                                        ii.onFalse.remove(ii.onFalse.size() - 1);
                                        list.add(i + 1, gotoMerged);
                                        allGotos.remove(gotoRemoved);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private void processIfs(List<GraphTargetItem> list) {
        for (int i = 0; i < list.size(); i++) {
            GraphTargetItem item = list.get(i);
            if ((item instanceof LoopItem) && (item instanceof Block)) {
                List<List<GraphTargetItem>> subs = ((Block) item).getSubs();
                for (List<GraphTargetItem> sub : subs) {
                    processIfs(sub);
                    checkContinueAtTheEnd(sub, ((LoopItem) item).loop);
                }
            } else if (item instanceof Block) {
                List<List<GraphTargetItem>> subs = ((Block) item).getSubs();
                for (List<GraphTargetItem> sub : subs) {
                    processIfs(sub);
                }
            }
            if (item instanceof IfItem) {
                IfItem ifi = (IfItem) item;
                List<GraphTargetItem> onTrue = ifi.onTrue;
                List<GraphTargetItem> onFalse = ifi.onFalse;
                if ((!onTrue.isEmpty()) && (!onFalse.isEmpty())) {
                    if (onTrue.get(onTrue.size() - 1) instanceof ContinueItem) {
                        if (onFalse.get(onFalse.size() - 1) instanceof ContinueItem) {
                            if (((ContinueItem) onTrue.get(onTrue.size() - 1)).loopId == ((ContinueItem) onFalse.get(onFalse.size() - 1)).loopId) {
                                onTrue.remove(onTrue.size() - 1);
                                list.add(i + 1, onFalse.remove(onFalse.size() - 1));
                            }
                        }
                    }
                }

                if ((!onTrue.isEmpty()) && (!onFalse.isEmpty())) {
                    GraphTargetItem last = onTrue.get(onTrue.size() - 1);
                    if ((last instanceof ExitItem) || (last instanceof ContinueItem) || (last instanceof BreakItem)) {
                        list.addAll(i + 1, onFalse);
                        onFalse.clear();
                    }
                }

                if ((!onTrue.isEmpty()) && (!onFalse.isEmpty())) {
                    if (onFalse.get(onFalse.size() - 1) instanceof ExitItem) {
                        if (onTrue.get(onTrue.size() - 1) instanceof ContinueItem) {
                            list.add(i + 1, onTrue.remove(onTrue.size() - 1));
                        }
                    }
                }
            }
        }

        //Same continues in onTrue and onFalse gets continue on parent level
    }

    protected List<GraphPart> getLoopsContinuesPreAndBreaks(List<Loop> loops) {
        List<GraphPart> ret = new ArrayList<>();
        for (Loop l : loops) {
            if (l.loopContinue != null) {
                ret.add(l.loopContinue);
            }
            if (l.loopPreContinue != null) {
                ret.add(l.loopPreContinue);
            }
            if (l.loopBreak != null) {
                ret.add(l.loopBreak);
            }
        }
        return ret;
    }

    protected List<GraphPart> getLoopsContinuesAndPre(List<Loop> loops) {
        List<GraphPart> ret = new ArrayList<>();
        for (Loop l : loops) {
            if (l.loopContinue != null) {
                ret.add(l.loopContinue);
            }
            if (l.loopPreContinue != null) {
                ret.add(l.loopPreContinue);
            }
        }
        return ret;
    }

    protected List<GraphPart> getLoopsContinues(List<Loop> loops) {
        List<GraphPart> ret = new ArrayList<>();
        for (Loop l : loops) {
            if (l.loopContinue != null) {
                ret.add(l.loopContinue);
            }
            /*if (l.loopPreContinue != null) {
             ret.add(l.loopPreContinue);
             }*/
        }
        return ret;
    }

    protected GraphTargetItem checkLoop(List<GraphTargetItem> output, GraphPart part, List<GraphPart> stopPart, List<Loop> loops) {
        if (stopPart.contains(part)) {
            return null;
        }

        GraphSourceItem firstIns = null;
        if (part != null) {
            if (part.start >= 0 && part.start < code.size()) {
                firstIns = code.get(part.start);
            }
        }

        for (Loop l : loops) {
            if (l.loopContinue == part) {
                return (new ContinueItem(null, firstIns, l.id));
            }
            if (l.loopBreak == part) {
                return (new BreakItem(null, firstIns, l.id));
            }
        }
        return null;
    }

    //TODO: Make this faster!!!
    private void getPrecontinues(String path, BaseLocalData localData, GraphPart parent, GraphPart part, Set<GraphPart> allParts, List<Loop> loops, List<GraphPart> stopPart) throws InterruptedException {
        try {
            markLevels(path, localData, part, allParts, loops);
        } catch (ThreadDeath | InterruptedException ex) {
            throw ex;
        } catch (Throwable ex) {
            //It is unusual code so markLevels failed, nevermind, it can still work
        }
        //Note: this also marks part as precontinue when there is if
        /*
         while(k<10){
         if(k==7){
         trace(a);
         }else{
         trace(b);
         }
         //precontinue
         k++;
         }
         */
        looploops:
        for (Loop l : loops) {
            if (l.loopContinue != null) {
                Set<GraphPart> uniqueRefs = new HashSet<>();
                uniqueRefs.addAll(l.loopContinue.refs);
                if (uniqueRefs.size() == 2) { //only one path - from precontinue
                    List<GraphPart> uniqueRefsList = new ArrayList<>(uniqueRefs);
                    if (uniqueRefsList.get(0).discoveredTime > uniqueRefsList.get(1).discoveredTime) { //latch node is discovered later
                        part = uniqueRefsList.get(0);
                    } else {
                        part = uniqueRefsList.get(1);
                    }
                    if (part == l.loopContinue) {
                        continue looploops;
                    }

                    while (part.refs.size() == 1) {
                        if (part.refs.get(0).nextParts.size() != 1) {
                            continue looploops;
                        }

                        part = part.refs.get(0);
                        if (part == l.loopContinue) {
                            break;
                        }
                    }
                    if (part.level == 0 && part != l.loopContinue) {
                        l.loopPreContinue = part;
                    }
                }
            }
        }
        /*clearLoops(loops);
         getPrecontinues(parent, part, loops, stopPart, 0, new ArrayList<GraphPart>());
         clearLoops(loops);*/
    }

    private void markLevels(String path, BaseLocalData localData, GraphPart part, Set<GraphPart> allParts, List<Loop> loops) throws InterruptedException {
        clearLoops(loops);
        markLevels(path, localData, part, allParts, loops, new ArrayList<>(), 1, new HashSet<>(), 0);
        clearLoops(loops);
    }

    private void markLevels(String path, BaseLocalData localData, GraphPart part, Set<GraphPart> allParts, List<Loop> loops, List<GraphPart> stopPart, int level, Set<GraphPart> visited, int recursionLevel) throws InterruptedException {
        if (stopPart == null) {
            stopPart = new ArrayList<>();
        }
        if (recursionLevel > allParts.size() + 1) {
            throw new RuntimeException(path + ": markLevels max recursion level reached");
        }

        if (stopPart.contains(part)) {
            //System.err.println("/stopped part " + part);
            return;
        }
        for (Loop el : loops) {
            if ((el.phase == 2) && (el.loopContinue == part)) {
                return;
            }
            if (el.phase != 1) {
                continue;
            }
            if (el.loopContinue == part) {
                return;
            }
            if (el.loopPreContinue == part) {
                return;
            }
            if (el.loopBreak == part) {
                return;
            }
        }

        if (visited.contains(part)) {
            part.level = 0;
            //System.err.println("set level " + part + " to zero");
        } else {
            visited.add(part);
            part.level = level;
            //System.err.println("set level " + part + " to " + level);
        }

        boolean isLoop = false;
        Loop currentLoop = null;
        for (Loop el : loops) {
            if ((el.phase == 0) && (el.loopContinue == part)) {
                isLoop = true;
                currentLoop = el;
                el.phase = 1;
                break;
            }
        }

        List<GraphPart> nextParts = checkPrecoNextParts(part);
        if (nextParts == null) {
            nextParts = part.nextParts;
        }
        nextParts = new ArrayList<>(nextParts);

        if (nextParts.size() == 2 && stopPart.contains(nextParts.get(1))) {
            nextParts.remove(1);
        }
        if (nextParts.size() >= 1 && stopPart.contains(nextParts.get(0))) {
            nextParts.remove(0);
        }

        if (nextParts.size() == 2) {
            GraphPart next = getCommonPart(localData, part, nextParts, loops, new ArrayList<>());//part.getNextPartPath(new ArrayList<GraphPart>());
            //System.err.println("- common part of " + nextParts.get(0) + " and " + nextParts.get(1) + " is " + next);
            List<GraphPart> stopParts2 = new ArrayList<>();  //stopPart);
            if (next != null) {
                stopParts2.add(next);
            } else if (!stopPart.isEmpty()) {
                stopParts2.add(stopPart.get(stopPart.size() - 1));
            }
            if (next != nextParts.get(0)) {
                // System.err.println("- going to branch 0 nextpart from " + part + " to " + nextParts.get(0));
                markLevels(path, localData, nextParts.get(0), allParts, loops, next == null ? stopPart : stopParts2, level + 1, visited, recursionLevel + 1);
            } else {
                //System.err.println("- branch 0 of " + part + " is skipped (=next)");
            }

            if (next != nextParts.get(1)) {
                //System.err.println("- going to branch 1 nextpart from " + part + " to " + nextParts.get(1));
                markLevels(path, localData, nextParts.get(1), allParts, loops, next == null ? stopPart : stopParts2, level + 1, visited, recursionLevel + 1);
            } else {
                //System.err.println("- branch 1 of " + part + " is skipped (=next)");
            }
            if (next != null) {
                //System.err.println("- going to next from " + part + " to " + next);
                markLevels(path, localData, next, allParts, loops, stopPart, level, visited, recursionLevel + 1);
            }
        }

        if (nextParts.size() > 2) {
            GraphPart next = getMostCommonPart(localData, nextParts, loops, new ArrayList<>());
            List<GraphPart> vis = new ArrayList<>();
            for (GraphPart p : nextParts) {
                if (vis.contains(p)) {
                    continue;
                }
                List<GraphPart> stopPart2 = new ArrayList<>(); //(stopPart);
                if (next != null) {
                    stopPart2.add(next);
                } else if (!stopPart.isEmpty()) {
                    stopPart2.add(stopPart.get(stopPart.size() - 1));
                }
                for (GraphPart p2 : nextParts) {
                    if (p2 == p) {
                        continue;
                    }
                    if (!stopPart2.contains(p2)) {
                        stopPart2.add(p2);
                    }
                }
                if (next != p) {
                    markLevels(path, localData, p, allParts, loops, stopPart2, level + 1, visited, recursionLevel + 1);
                    vis.add(p);
                }
            }
            if (next != null) {
                markLevels(path, localData, next, allParts, loops, stopPart, level, visited, recursionLevel + 1);
            }
        }

        if (nextParts.size() == 1) {
            //System.err.println("going to one nexpart from " + part + " to " + nextParts.get(0));
            markLevels(path, localData, nextParts.get(0), allParts, loops, stopPart, level, visited, recursionLevel + 1);
        }

        for (GraphPart t : part.throwParts) {
            if (!visited.contains(t)) {
                List<GraphPart> stopPart2 = new ArrayList<>();
                List<GraphPart> cmn = new ArrayList<>();
                cmn.add(part);
                cmn.add(t);
                GraphPart next = getCommonPart(localData, part, cmn, loops, new ArrayList<>());
                if (next != null) {
                    stopPart2.add(next);
                } else {
                    stopPart2 = stopPart;
                }

                markLevels(path, localData, t, allParts, loops, stopPart2, level, visited, recursionLevel + 1);
            }
        }

        if (isLoop) {
            if (currentLoop != null && currentLoop.loopBreak != null) {
                currentLoop.phase = 2;
                //System.err.println("- going to break of loop " + currentLoop.loopBreak);
                markLevels(path, localData, currentLoop.loopBreak, allParts, loops, stopPart, level, visited, recursionLevel + 1);
            }
        }
    }

    private void checkContinueAtTheEnd(List<GraphTargetItem> commands, Loop loop) {
        if (!commands.isEmpty()) {
            int i = commands.size() - 1;
            for (; i >= 0; i--) {
                if (commands.get(i) instanceof ContinueItem) {
                    continue;
                }
                if (commands.get(i) instanceof BreakItem) {
                    continue;
                }
                break;
            }
            if (i < commands.size() - 1) {
                for (int k = i + 2; k < commands.size(); k++) {
                    commands.remove(k);
                }
            }
            if (commands.get(commands.size() - 1) instanceof ContinueItem) {
                if (((ContinueItem) commands.get(commands.size() - 1)).loopId == loop.id) {
                    commands.remove(commands.size() - 1);
                }
            }
        }
    }

    protected boolean isEmpty(List<GraphTargetItem> output) {
        if (output.isEmpty()) {
            return true;
        }
        if (output.size() == 1) {
            if (output.get(0) instanceof MarkItem) {
                return true;
            }
        }
        return false;
    }

    protected List<GraphTargetItem> check(List<GotoItem> foundGotos, List<GraphPartEdge> gotoTargets, Map<GraphPart, List<GraphTargetItem>> partCodes, Map<GraphPart, Integer> partCodePos, GraphSource code, BaseLocalData localData, Set<GraphPart> allParts, TranslateStack stack, GraphPart parent, GraphPart part, List<GraphPart> stopPart, List<Loop> loops, List<GraphTargetItem> output, Loop currentLoop, int staticOperation, String path) throws InterruptedException {
        return null;
    }

    protected GraphPart checkPart(TranslateStack stack, BaseLocalData localData, GraphPart part, Set<GraphPart> allParts) {
        return part;
    }

    //@SuppressWarnings("unchecked")
    protected GraphTargetItem translatePartGetStack(BaseLocalData localData, GraphPart part, TranslateStack stack, int staticOperation) throws InterruptedException {
        stack = (TranslateStack) stack.clone();
        translatePart(localData, part, stack, staticOperation, null);
        return stack.pop();
    }

    protected List<GraphTargetItem> translatePart(BaseLocalData localData, GraphPart part, TranslateStack stack, int staticOperation, String path) throws InterruptedException {
        List<GraphPart> sub = part.getSubParts();
        List<GraphTargetItem> ret = new ArrayList<>();
        int end;
        for (GraphPart p : sub) {
            if (p.end == -1) {
                p.end = code.size() - 1;
            }
            if (p.start == code.size()) {
                continue;
            } else if (p.end == code.size()) {
                p.end--;
            }
            end = p.end;
            int start = p.start;
            ret.addAll(code.translatePart(part, localData, stack, start, end, staticOperation, path));
        }
        return ret;
    }

    private void markBranchEnd(List<GraphTargetItem> items) {
        if (!items.isEmpty()) {
            if (items.get(items.size() - 1) instanceof BreakItem) {
                return;
            }
            if (items.get(items.size() - 1) instanceof ContinueItem) {
                return;
            }
            if (items.get(items.size() - 1) instanceof ExitItem) {
                return;
            }
        }
        items.add(new MarkItem("finish"));
    }

    private static GraphTargetItem getLastNoEnd(List<GraphTargetItem> list) {
        if (list.isEmpty()) {
            return null;
        }
        if (list.get(list.size() - 1) instanceof ScriptEndItem) {
            if (list.size() >= 2) {
                return list.get(list.size() - 2);
            }
            return list.get(list.size() - 1);
        }
        return list.get(list.size() - 1);
    }

    private static void removeLastNoEnd(List<GraphTargetItem> list) {
        if (list.isEmpty()) {
            return;
        }
        if (list.get(list.size() - 1) instanceof ScriptEndItem) {
            if (list.size() >= 2) {
                list.remove(list.size() - 2);
            }
            return;
        }
        list.remove(list.size() - 1);
    }

    protected List<GraphTargetItem> printGraph(List<GotoItem> foundGotos, List<GraphPartEdge> gotoTargets, Map<GraphPart, List<GraphTargetItem>> partCodes, Map<GraphPart, Integer> partCodePos, BaseLocalData localData, TranslateStack stack, Set<GraphPart> allParts, GraphPart parent, GraphPart part, List<GraphPart> stopPart, List<Loop> loops, int staticOperation, String path) throws InterruptedException {
        return printGraph(foundGotos, gotoTargets, partCodes, partCodePos, new HashSet<>(), localData, stack, allParts, parent, part, stopPart, loops, null, staticOperation, path, 0);
    }

    protected GraphTargetItem checkLoop(List<GraphTargetItem> output, LoopItem loopItem, BaseLocalData localData, List<Loop> loops) {
        return loopItem;
    }

    private void clearLoops(List<Loop> loops) {
        for (Loop l : loops) {
            l.phase = 0;
        }
    }

    private void getLoops(BaseLocalData localData, GraphPart part, List<Loop> loops, List<GraphPart> stopPart) throws InterruptedException {
        clearLoops(loops);
        getLoops(localData, part, loops, stopPart, true, 1, new ArrayList<>());
        clearLoops(loops);
    }

    protected boolean canBeBreakCandidate(GraphPart part) {
        return true;
    }

    private void getLoops(BaseLocalData localData, GraphPart part, List<Loop> loops, List<GraphPart> stopPart, boolean first, int level, List<GraphPart> visited) throws InterruptedException {

        if (part == null) {
            return;
        }

        part = checkPart(null, localData, part, null);
        if (part == null) {
            return;
        }
        if (!visited.contains(part)) {
            visited.add(part);
        }

        if (debugGetLoops) {
            System.err.println("getloops: " + part);
        }
        //List<GraphPart> loopContinues = getLoopsContinues(loops);
        Loop lastP1 = null;
        for (Loop el : loops) {
            if ((el.phase == 1) && el.loopBreak == null) { //break not found yet
                if (el.loopContinue != part) {
                    lastP1 = el;

                } else {
                    lastP1 = null;
                }

            }
        }
        if (lastP1 != null && canBeBreakCandidate(part)) {
            if (lastP1.breakCandidates.contains(part)) {
                lastP1.breakCandidates.add(part);
                lastP1.breakCandidatesLevels.add(level);
                return;
            } else {
                List<Loop> loops2 = new ArrayList<>(loops);
                loops2.remove(lastP1);
                if (!part.leadsTo(localData, this, code, lastP1.loopContinue, loops2, new ArrayList<>())) {
                    if (lastP1.breakCandidatesLocked == 0) {
                        if (debugGetLoops) {
                            System.err.println("added breakCandidate " + part + " to " + lastP1);
                        }

                        lastP1.breakCandidates.add(part);
                        lastP1.breakCandidatesLevels.add(level);
                        return;
                    }
                }
            }
        }

        for (Loop el : loops) {
            if (el.loopContinue == part) {
                return;
            }
        }

        if (stopPart != null && stopPart.contains(part)) {
            return;
        }
        part.level = level;

        boolean isLoop = part.leadsTo(localData, this, code, part, loops, new ArrayList<>());
        Loop currentLoop = null;
        if (isLoop) {
            currentLoop = new Loop(loops.size(), part, null);
            currentLoop.phase = 1;
            loops.add(currentLoop);
            //loopContinues.add(part);
        }

        if (part.nextParts.size() == 2) {

            List<GraphPart> nps;/* = new ArrayList<>(part.nextParts);
             for(int i=0;i<nps.size();i++){
             nps.set(i,getNextNoJump(nps.get(i),localData));
             }
             if(nps.get(0) == nps.get(1)){
             nps = part.nextParts;
             }*/

            nps = part.nextParts;
            GraphPart next = getCommonPart(localData, part, nps, loops, new ArrayList<>());
            List<GraphPart> stopPart2 = stopPart == null ? new ArrayList<>() : new ArrayList<>(stopPart);
            if (next != null) {
                stopPart2.add(next);
            }
            if (next != nps.get(0)) {
                getLoops(localData, nps.get(0), loops, stopPart2, false, level + 1, visited);
            }
            if (next != nps.get(1)) {
                getLoops(localData, nps.get(1), loops, stopPart2, false, level + 1, visited);
            }
            if (next != null) {
                getLoops(localData, next, loops, stopPart, false, level, visited);
            }
        } else if (part.nextParts.size() > 2) {
            GraphPart next = getNextCommonPart(localData, part, loops, new ArrayList<>());

            for (GraphPart p : part.nextParts) {
                List<GraphPart> stopPart2 = stopPart == null ? new ArrayList<>() : new ArrayList<>(stopPart);
                if (next != null) {
                    stopPart2.add(next);
                }
                for (GraphPart p2 : part.nextParts) {
                    if (p2 == p) {
                        continue;
                    }
                    if (!stopPart2.contains(p2)) {
                        stopPart2.add(p2);
                    }
                }
                if (next != p) {
                    getLoops(localData, p, loops, stopPart2, false, level + 1, visited);
                }
            }
            if (next != null) {
                getLoops(localData, next, loops, stopPart, false, level, visited);
            }
        } else if (part.nextParts.size() == 1) {
            getLoops(localData, part.nextParts.get(0), loops, stopPart, false, level, visited);
        }

        List<Loop> loops2 = new ArrayList<>(loops);
        for (Loop l : loops2) {
            l.breakCandidatesLocked++;
        }
        for (GraphPart t : part.throwParts) {
            if (!visited.contains(t)) {
                getLoops(localData, t, loops, stopPart, false, level, visited);
            }
        }
        for (Loop l : loops2) {
            l.breakCandidatesLocked--;
        }

        if (isLoop && currentLoop != null) {
            GraphPart found;
            Map<GraphPart, Integer> removed = new HashMap<>();
            do {
                found = null;
                for (int i = 0; i < currentLoop.breakCandidates.size(); i++) {
                    GraphPart ch = checkPart(null, localData, currentLoop.breakCandidates.get(i), null);
                    if (ch == null) {
                        currentLoop.breakCandidates.remove(i);
                        i--;
                    }
                }
                loopcand:
                for (GraphPart cand : currentLoop.breakCandidates) {
                    for (GraphPart cand2 : currentLoop.breakCandidates) {
                        if (cand == cand2) {
                            continue;
                        }
                        if (cand.leadsTo(localData, this, code, cand2, loops, new ArrayList<>())) {
                            int lev1 = Integer.MAX_VALUE;
                            int lev2 = Integer.MAX_VALUE;
                            for (int i = 0; i < currentLoop.breakCandidates.size(); i++) {
                                if (currentLoop.breakCandidates.get(i) == cand) {
                                    if (currentLoop.breakCandidatesLevels.get(i) < lev1) {
                                        lev1 = currentLoop.breakCandidatesLevels.get(i);
                                    }
                                }
                                if (currentLoop.breakCandidates.get(i) == cand2) {
                                    if (currentLoop.breakCandidatesLevels.get(i) < lev2) {
                                        lev2 = currentLoop.breakCandidatesLevels.get(i);
                                    }
                                }
                            }
                            //
                            if (lev1 <= lev2) {
                                found = cand2;
                            } else {
                                found = cand;
                            }
                            break loopcand;
                        }
                    }
                }
                if (found != null) {
                    int maxlevel = 0;
                    while (currentLoop.breakCandidates.contains(found)) {
                        int ind = currentLoop.breakCandidates.indexOf(found);
                        currentLoop.breakCandidates.remove(ind);
                        int lev = currentLoop.breakCandidatesLevels.remove(ind);
                        if (lev > maxlevel) {
                            maxlevel = lev;
                        }
                    }
                    if (removed.containsKey(found)) {
                        if (removed.get(found) > maxlevel) {
                            maxlevel = removed.get(found);
                        }
                    }
                    removed.put(found, maxlevel);
                }
            } while ((found != null) && (currentLoop.breakCandidates.size() > 1));

            Map<GraphPart, Integer> count = new HashMap<>();
            GraphPart winner = null;
            int winnerCount = 0;
            for (GraphPart cand : currentLoop.breakCandidates) {

                if (!count.containsKey(cand)) {
                    count.put(cand, 0);
                }
                count.put(cand, count.get(cand) + 1);
                boolean otherBreakCandidate = false;
                for (Loop el : loops) {
                    if (el == currentLoop) {
                        continue;
                    }
                    if (el.breakCandidates.contains(cand)) {
                        otherBreakCandidate = true;
                        break;
                    }
                }
                if (otherBreakCandidate) {
                } else if (count.get(cand) > winnerCount) {
                    winnerCount = count.get(cand);
                    winner = cand;
                } else if (count.get(cand) == winnerCount && winner != null) {
                    if (cand.path.length() < winner.path.length()) {
                        winner = cand;
                    }
                }
            }
            for (int i = 0; i < currentLoop.breakCandidates.size(); i++) {
                GraphPart cand = currentLoop.breakCandidates.get(i);
                if (cand != winner) {
                    int lev = currentLoop.breakCandidatesLevels.get(i);
                    if (removed.containsKey(cand)) {
                        if (removed.get(cand) > lev) {
                            lev = removed.get(cand);
                        }
                    }
                    removed.put(cand, lev);
                }
            }
            currentLoop.loopBreak = winner;
            currentLoop.phase = 2;
            boolean start = false;
            for (int l = 0; l < loops.size(); l++) {
                Loop el = loops.get(l);
                if (start) {
                    el.phase = 1;
                }
                if (el == currentLoop) {
                    start = true;
                }
            }
            List<GraphPart> removedVisited = new ArrayList<>();
            for (GraphPart r : removed.keySet()) {
                if (removedVisited.contains(r)) {
                    continue;
                }
                getLoops(localData, r, loops, stopPart, false, removed.get(r), visited);
                removedVisited.add(r);
            }
            start = false;
            for (int l = 0; l < loops.size(); l++) {
                Loop el = loops.get(l);
                if (el == currentLoop) {
                    start = true;
                }
                if (start) {
                    el.phase = 2;
                }
            }
            getLoops(localData, currentLoop.loopBreak, loops, stopPart, false, level, visited);
        }
    }

    protected List<GraphTargetItem> printGraph(List<GotoItem> foundGotos, List<GraphPartEdge> gotoTargets, Map<GraphPart, List<GraphTargetItem>> partCodes, Map<GraphPart, Integer> partCodePos, Set<GraphPart> visited, BaseLocalData localData, TranslateStack stack, Set<GraphPart> allParts, GraphPart parent, GraphPart part, List<GraphPart> stopPart, List<Loop> loops, List<GraphTargetItem> ret, int staticOperation, String path, int recursionLevel) throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException();
        }
        if (stopPart == null) {
            stopPart = new ArrayList<>();
        }
        if (recursionLevel > allParts.size() + 1) {
            throw new TranslateException("printGraph max recursion level reached.");
        }

        if (ret == null) {
            ret = new ArrayList<>();
        }
        //try {

        if (debugPrintGraph) {
            System.err.println("PART " + part + " nextsize:" + part.nextParts.size());
        }

        /*while (((part != null) && (part.getHeight() == 1)) && (code.size() > part.start) && (code.get(part.start).isJump())) {  //Parts with only jump in it gets ignored

         if (part == stopPart) {
         return ret;
         }
         GraphTargetItem lop = checkLoop(part.nextParts.get(0), stopPart, loops);
         if (lop == null) {
         part = part.nextParts.get(0);
         } else {
         break;
         }
         }*/
        if (part == null) {
            return ret;
        }
        part = checkPart(stack, localData, part, allParts);
        if (part == null) {
            return ret;
        }

        if (part.ignored) {
            return ret;
        }

        //List<GraphPart> loopContinues = getLoopsContinues(loops);
        boolean isLoop = false;
        Loop currentLoop = null;
        for (Loop el : loops) {
            if ((el.loopContinue == part) && (el.phase == 0)) {
                currentLoop = el;
                currentLoop.phase = 1;
                isLoop = true;
                break;
            }
        }

        if (debugPrintGraph) {
            System.err.println("loopsize:" + loops.size());
        }
        for (int l = loops.size() - 1; l >= 0; l--) {
            Loop el = loops.get(l);
            if (el == currentLoop) {
                if (debugPrintGraph) {
                    System.err.println("ignoring current loop " + el);
                }
                continue;
            }
            if (el.phase != 1) {
                if (debugPrintGraph) {
                    System.err.println("ignoring loop " + el);
                }
                continue;
            }
            if (el.loopBreak == part) {
                if (currentLoop != null) {
                    currentLoop.phase = 0;
                }
                if (debugPrintGraph) {
                    System.err.println("Adding break");
                }
                ret.add(new BreakItem(null, localData.lineStartInstruction, el.id));
                return ret;
            }
            if (el.loopPreContinue == part) {
                if (currentLoop != null) {
                    currentLoop.phase = 0;
                }
                if (debugPrintGraph) {
                    System.err.println("Adding precontinue");
                }
                ret.add(new ContinueItem(null, localData.lineStartInstruction, el.id));
                return ret;
            }
            if (el.loopContinue == part) {
                if (currentLoop != null) {
                    currentLoop.phase = 0;
                }
                if (debugPrintGraph) {
                    System.err.println("Adding continue");
                }
                ret.add(new ContinueItem(null, localData.lineStartInstruction, el.id));
                return ret;
            }
        }

        if (debugPrintGraph) {
            System.err.println("stopParts: " + pathToString(stopPart));
        }

        if (stopPart.contains(part)) {
            if (currentLoop != null) {
                currentLoop.phase = 0;
            }
            if (debugPrintGraph) {
                System.err.println("Stopped on part " + part);
            }
            return ret;
        }

        if (code.size() <= part.start) {
            ret.add(new ScriptEndItem());
            return ret;
        }

        GraphPartEdge edge = new GraphPartEdge(parent, part);
        if (gotoTargets.contains(edge)) {
            if (debugPrintGraph) {
                //System.err.println("Already visited part " + part + ", adding goto");
            }
            String labelName = "addr" + part.start;
            GotoItem gi = new GotoItem(null, localData.lineStartInstruction, labelName);
            boolean targetCommandsFound = false;
            for (int r = foundGotos.size() - 1; r >= 0; r--) {
                GotoItem gi2 = foundGotos.get(r);
                if (labelName.equals(gi2.labelName)) {
                    gi.targetCommands = gi2.targetCommands;
                    gi2.targetCommands = null;
                    targetCommandsFound = true;
                    break;
                }
            }
            makeAllCommands(ret, stack);
            if (!targetCommandsFound) {
                List<GraphPartEdge> newGotoTargets = new ArrayList<>(gotoTargets);
                removeEdgeToFromList(newGotoTargets, part);
                gi.targetCommands = printGraph(foundGotos, newGotoTargets, partCodes, partCodePos, localData, stack, allParts, parent, part, stopPart, loops, staticOperation, path);
                processIfs(gi.targetCommands);
                if (!gi.targetCommands.isEmpty() && (gi.targetCommands.get(gi.targetCommands.size() - 1) instanceof ContinueItem)) {
                    ContinueItem cnt = (ContinueItem) gi.targetCommands.get(gi.targetCommands.size() - 1);
                    for (Loop l : loops) {
                        if (l.id == cnt.loopId && l.backEdges.size() == 1) {
                            gi.targetCommands.remove(cnt);
                            l.precontinueCommands = gi.targetCommands;
                            l.loopPreContinue = part;
                            //removeEdgeToFromList(gotoTargets, part);
                            ret.add(cnt);
                            return ret;
                        }
                    }
                }
            }
            foundGotos.add(gi);
            ret.add(gi);

            return ret;
        } else if (visited.contains(part)) {
            String labelName = "addr" + part.start;
            List<GraphTargetItem> firstCode = partCodes.get(part);
            int firstCodePos = partCodePos.get(part);
            if (firstCodePos > firstCode.size()) {
                firstCodePos = firstCode.size();
            }
            if (firstCode.size() > firstCodePos && (firstCode.get(firstCodePos) instanceof LabelItem)) {
                labelName = ((LabelItem) firstCode.get(firstCodePos)).labelName;
            } else {
                firstCode.add(firstCodePos, new LabelItem(null, localData.lineStartInstruction, labelName));
            }
            ret.add(new GotoItem(null, localData.lineStartInstruction, labelName));
            return ret;
        } else {
            visited.add(part);
            partCodes.put(part, ret);
            partCodePos.put(part, ret.size());
        }
        List<GraphTargetItem> currentRet = ret;
        UniversalLoopItem loopItem = null;
        TranslateStack sPreLoop = stack;
        if (isLoop) {
            //makeAllCommands(currentRet, stack);
            stack = (TranslateStack) stack.clone();

            //hack for as1/2 for..in to get enumeration through
            GraphTargetItem topBsr = !stack.isEmpty() && (stack.peek() instanceof BranchStackResistant) ? stack.peek() : null;
            stack.clear();
            if (topBsr != null) {
                stack.push(topBsr);
            }
            loopItem = new UniversalLoopItem(null, localData.lineStartInstruction, currentLoop, new ArrayList<>());
            //loopItem.commands=printGraph(visited, localData, stack, allParts, parent, part, stopPart, loops);
            currentRet.add(loopItem);
            currentRet = loopItem.commands;
            //return ret;
        }

        boolean parseNext = true;

        //****************************DECOMPILING PART*************
        List<GraphTargetItem> output = new ArrayList<>();

        List<GraphPart> parts = new ArrayList<>();
        if (part instanceof GraphPartMulti) {
            parts = ((GraphPartMulti) part).parts;
        } else {
            parts.add(part);
        }
        for (GraphPart p : parts) {
            int end = p.end;
            int start = p.start;

            output.addAll(code.translatePart(p, localData, stack, start, end, staticOperation, path));
            if ((end >= code.size() - 1) && p.nextParts.isEmpty()) {
                output.add(new ScriptEndItem());
            }
        }

        if (parseNext) {
            List<GraphTargetItem> retCheck = check(foundGotos, gotoTargets, partCodes, partCodePos, code, localData, allParts, stack, parent, part, stopPart, loops, output, currentLoop, staticOperation, path);
            if (retCheck != null) {
                if (!retCheck.isEmpty()) {
                    currentRet.addAll(retCheck);
                }
                parseNext = false;
                //return ret;
            } else {
                currentRet.addAll(output);
            }
        }
//********************************END PART DECOMPILING
        if (parseNext) {

            if (part.nextParts.size() > 2) {
                GraphPart next = getMostCommonPart(localData, part.nextParts, loops, new ArrayList<>());
                List<GraphPart> vis = new ArrayList<>();
                GraphTargetItem originalSwitchedItem = stack.pop();
                makeAllCommands(currentRet, stack);
                GraphTargetItem switchedItem = originalSwitchedItem;
                if ((switchedItem instanceof PopItem) && !currentRet.isEmpty() && (currentRet.get(currentRet.size() - 1) instanceof IfItem)) {
                    switchedItem = currentRet.get(currentRet.size() - 1);
                }

                List<GraphTargetItem> caseValues = new ArrayList<>();
                List<List<GraphTargetItem>> caseCommands = new ArrayList<>();
                List<Integer> valueMappings = new ArrayList<>();
                Loop swLoop = new Loop(loops.size(), null, next);
                //removeEdgeToFromList(gotoTargets, next);
                swLoop.phase = 1;
                loops.add(swLoop);
                boolean first = false;
                int pos;

                Map<Integer, GraphTargetItem> caseExpressions = new HashMap<>();
                Map<Integer, GraphTargetItem> caseExpressionLeftSides = new HashMap<>();
                Map<Integer, GraphTargetItem> caseExpressionRightSides = new HashMap<>();

                GraphTargetItem it = switchedItem;
                int defaultBranch = 0;
                boolean hasExpr = false;
                List<GraphTargetItem> commaCommands = new ArrayList<>();
                Map<Integer, List<GraphTargetItem>> caseCommaCommands = new HashMap<>();

                while ((it instanceof TernarOpItem) || (it instanceof IfItem)) {

                    if (it instanceof IfItem) {
                        IfItem ii = (IfItem) it;
                        if (ii.expression instanceof EqualsTypeItem) {
                            if (!ii.onFalse.isEmpty() && !ii.onTrue.isEmpty()
                                    && ii.onTrue.get(ii.onTrue.size() - 1) instanceof PushItem
                                    && ii.onTrue.get(ii.onTrue.size() - 1).value instanceof IntegerValueTypeItem) {
                                int cpos = ((IntegerValueTypeItem) ii.onTrue.get(ii.onTrue.size() - 1).value).intValue();
                                caseCommaCommands.put(cpos, commaCommands);
                                caseExpressionLeftSides.put(cpos, ((EqualsTypeItem) ii.expression).getLeftSide());
                                caseExpressionRightSides.put(cpos, ((EqualsTypeItem) ii.expression).getRightSide());
                                commaCommands = new ArrayList<>();
                                for (int f = 0; f < ii.onFalse.size() - 1; f++) {
                                    commaCommands.add(ii.onFalse.get(f));
                                }
                                it = ii.onFalse.get(ii.onFalse.size() - 1);
                                if (it instanceof PushItem) {
                                    it = it.value;
                                }
                            } else {
                                break;
                            }
                        } else if (ii.expression instanceof FalseItem && !ii.onFalse.isEmpty()) {
                            it = ii.onFalse.get(ii.onFalse.size() - 1);
                        } else if (ii.expression instanceof TrueItem && !ii.onTrue.isEmpty()) {
                            it = ii.onTrue.get(ii.onTrue.size() - 1);
                        } else {
                            break;
                        }
                    } else if (it instanceof TernarOpItem) {
                        TernarOpItem to = (TernarOpItem) it;
                        if (to.expression instanceof EqualsTypeItem) {
                            if (to.onTrue instanceof IntegerValueTypeItem) {
                                int cpos = ((IntegerValueTypeItem) to.onTrue).intValue();
                                caseExpressionLeftSides.put(cpos, ((EqualsTypeItem) to.expression).getLeftSide());
                                caseExpressionRightSides.put(cpos, ((EqualsTypeItem) to.expression).getRightSide());
                                caseCommaCommands.put(cpos, commaCommands);
                                commaCommands = new ArrayList<>();
                                it = to.onFalse;
                            } else {
                                break;
                            }
                        } else if (to.expression instanceof FalseItem) {
                            it = to.onFalse;
                        } else if (to.expression instanceof TrueItem) {
                            it = to.onTrue;
                        } else {
                            break;
                        }
                    }
                }

                if (switchedItem != originalSwitchedItem && !caseExpressionRightSides.isEmpty()) {
                    currentRet.remove(currentRet.size() - 1);
                } else {
                    switchedItem = originalSwitchedItem;
                }

                //int ignoredBranch = -1;
                if (it instanceof IntegerValueTypeItem) {
                    defaultBranch = ((IntegerValueTypeItem) it).intValue();
                }

                Map<Integer, GraphTargetItem> caseExpressionOtherSides = caseExpressions;
                if (!caseExpressionRightSides.isEmpty()) {
                    GraphTargetItem firstItem;
                    firstItem = (GraphTargetItem) caseExpressionRightSides.values().toArray()[0];
                    boolean sameRight = true;
                    for (GraphTargetItem cit : caseExpressionRightSides.values()) {
                        if (!cit.equals(firstItem)) {
                            sameRight = false;
                            break;
                        }
                    }

                    if (sameRight) {
                        caseExpressions = caseExpressionLeftSides;
                        caseExpressionOtherSides = caseExpressionRightSides;
                        switchedItem = firstItem;
                        hasExpr = true;
                    } else {
                        firstItem = (GraphTargetItem) caseExpressionLeftSides.values().toArray()[0];

                        boolean sameLeft = true;
                        for (GraphTargetItem cit : caseExpressionLeftSides.values()) {
                            if (!cit.equals(firstItem)) {
                                sameLeft = false;
                                break;
                            }
                        }
                        if (sameLeft) {
                            caseExpressions = caseExpressionRightSides;
                            caseExpressionOtherSides = caseExpressionLeftSides;
                            switchedItem = firstItem;
                            hasExpr = true;
                        }
                    }
                }

                first = true;
                pos = 0;
                //This is tied to AS3 switch implementation which has nextparts switched from index 1. TODO: Make more universal

                GraphPart defaultPart = hasExpr ? part.nextParts.get(1 + defaultBranch) : part.nextParts.get(0);
                //int defaultNum = hasExpr ? 1 + defaultBranch : 0;

                for (int i = 1; i < part.nextParts.size(); i++) {
                    if (caseExpressions.containsKey(pos)) {
                        GraphTargetItem expr = caseExpressions.get(pos);
                        if (caseCommaCommands.get(pos).size() > 0) {
                            List<GraphTargetItem> exprCommaCommands = new ArrayList<>(caseCommaCommands.get(pos));
                            exprCommaCommands.add(expr);
                            expr = new CommaExpressionItem(null, expr.lineStartItem, exprCommaCommands);
                        }
                        caseValues.add(expr);
                    } else if (part.nextParts.get(i) == defaultPart) {
                        caseValues.add(new DefaultItem());
                    } else {
                        caseValues.add(new IntegerValueItem(null, localData.lineStartInstruction, pos));
                    }
                    pos++;
                }

                first = true;
                pos = 0;
                List<GraphTargetItem> nextCommands = new ArrayList<>();
                for (int i = 1; i < part.nextParts.size(); i++) {
                    //gotoTargets.remove(new GraphPartEdge(next, part.nextParts.get(i)));
                }
                for (int i = 1; i < part.nextParts.size(); i++) {
                    GraphPart p = part.nextParts.get(i);

                    /*if (pos == ignoredBranch) {
                     pos++;
                     continue;
                     }*/
                    //if (p != defaultPart)
                    {
                        if (vis.contains(p)) {
                            valueMappings.add(caseCommands.size() - 1);
                            continue;
                        }
                        valueMappings.add(caseCommands.size());
                    }
                    List<GraphPart> stopPart2 = new ArrayList<>();
                    if (next != null) {
                        stopPart2.add(next);
                    } else if (!stopPart.isEmpty()) {
                        stopPart2.add(stopPart.get(stopPart.size() - 1));
                    }
                    for (GraphPart p2 : part.nextParts) {
                        if (p2 == p) {
                            continue;
                        }
                        if (!stopPart2.contains(p2)) {
                            stopPart2.add(p2);
                        }
                    }
                    if (next != p) {
                        //if (p == defaultPart && !defaultCommands.isEmpty()) {
                        //ignore
                        //} else
                        {
                            TranslateStack s2 = (TranslateStack) stack.clone();
                            s2.clear();
                            nextCommands = printGraph(foundGotos, gotoTargets, partCodes, partCodePos, visited, prepareBranchLocalData(localData), s2, allParts, part, p, stopPart2, loops, null, staticOperation, path, recursionLevel + 1);
                            makeAllCommands(nextCommands, s2);
                            caseCommands.add(nextCommands);
                            vis.add(p);
                        }
                    } else {
                        caseCommands.add(nextCommands);
                    }
                    first = false;
                    pos++;
                }

                //If the lastone is default empty and alone, remove it
                if (!caseCommands.isEmpty()) {
                    List<GraphTargetItem> lastc = caseCommands.get(caseCommands.size() - 1);
                    if (!lastc.isEmpty() && (lastc.get(lastc.size() - 1) instanceof BreakItem)) {
                        BreakItem bi = (BreakItem) lastc.get(lastc.size() - 1);
                        if (bi.loopId == swLoop.id) {
                            lastc.remove(lastc.size() - 1);
                        }
                    }
                    if (lastc.isEmpty()) {
                        int cnt = 0;
                        if (caseValues.get(caseValues.size() - 1) instanceof DefaultItem) {
                            for (int i = valueMappings.size() - 1; i >= 0; i--) {
                                if (valueMappings.get(i) == caseCommands.size() - 1) {
                                    cnt++;
                                }
                            }
                            if (cnt == 1) {
                                caseValues.remove(caseValues.size() - 1);
                                valueMappings.remove(valueMappings.size() - 1);
                                caseCommands.remove(lastc);
                            }
                        }
                    }
                }
                //remove last break from last section
                if (!caseCommands.isEmpty()) {
                    List<GraphTargetItem> lastc = caseCommands.get(caseCommands.size() - 1);
                    if (!lastc.isEmpty() && (lastc.get(lastc.size() - 1) instanceof BreakItem)) {
                        BreakItem bi = (BreakItem) lastc.get(lastc.size() - 1);
                        if (bi.loopId == swLoop.id) {
                            lastc.remove(lastc.size() - 1);
                        }
                    }
                }
                SwitchItem sw = new SwitchItem(null, localData.lineStartInstruction, swLoop, switchedItem, caseValues, caseCommands, valueMappings);
                checkSwitch(localData, sw, caseExpressionOtherSides, currentRet);
                currentRet.add(sw);
                //TADY
                swLoop.phase = 2;
                if (next != null) {
                    currentRet.addAll(printGraph(foundGotos, gotoTargets, partCodes, partCodePos, visited, localData, stack, allParts, part, next, stopPart, loops, null, staticOperation, path, recursionLevel + 1));
                }
                pos++;
            } //else
            GraphPart nextOnePart = null;
            if (part.nextParts.size() == 2) {
                GraphTargetItem expr = stack.pop();
                /*if (expr instanceof LogicalOpItem) {
                 expr = ((LogicalOpItem) expr).invert();
                 } else {
                 expr = new NotItem(null, expr);
                 }*/
                if (nextOnePart == null) {

                    List<GraphPart> nps;
                    nps = part.nextParts;
                    boolean isEmpty = nps.get(0) == nps.get(1);

                    GraphPart next = getCommonPart(localData, part, nps, loops, gotoTargets);
                    //System.err.println("on part " + part + ", next: " + next);
                    TranslateStack trueStack = (TranslateStack) stack.clone();
                    TranslateStack falseStack = (TranslateStack) stack.clone();

                    //hack for as1/2 for..in to get enumeration through
                    GraphTargetItem topBsr = !stack.isEmpty() && (stack.peek() instanceof BranchStackResistant) ? stack.peek() : null;
                    trueStack.clear();
                    falseStack.clear();
                    if (topBsr != null) {
                        trueStack.add(topBsr);
                        falseStack.add(topBsr);
                    }
                    if (isEmpty) {
                        next = nps.get(0);
                    }
                    boolean hasOntrue = nps.get(1) != next;
                    boolean hasOnFalse = nps.get(0) != next;

                    List<GraphPart> stopPart2 = new ArrayList<>(stopPart);

                    if ((!isEmpty) && (next != null)) {
                        stopPart2.add(next);
                    }

                    List<GraphTargetItem> onTrue = new ArrayList<>();
                    if (!isEmpty && hasOntrue) {
                        onTrue = printGraph(foundGotos, gotoTargets, partCodes, partCodePos, visited, prepareBranchLocalData(localData), trueStack, allParts, part, nps.get(1), stopPart2, loops, null, staticOperation, path, recursionLevel + 1);
                    }
                    List<GraphTargetItem> onFalse = new ArrayList<>();

                    if (!isEmpty && hasOnFalse) {
                        onFalse = printGraph(foundGotos, gotoTargets, partCodes, partCodePos, visited, prepareBranchLocalData(localData), falseStack, allParts, part, nps.get(0), stopPart2, loops, null, staticOperation, path, recursionLevel + 1);
                    }
                    //List<GraphTargetItem> out2 = new ArrayList<>();
                    //makeAllCommands(out2, stack);
                    makeAllCommands(onTrue, trueStack);
                    makeAllCommands(onFalse, falseStack);

                    List<GraphTargetItem> filteredOnTrue = filter(onTrue);
                    List<GraphTargetItem> filteredOnFalse = filter(onFalse);

                    if (!isEmpty(filteredOnTrue) && !isEmpty(filteredOnFalse) && filteredOnTrue.size() == 1 && filteredOnFalse.size() == 1 && (filteredOnTrue.get(0) instanceof PushItem) && (filteredOnFalse.get(0) instanceof PushItem)) {
                        stack.push(new TernarOpItem(null, localData.lineStartInstruction, expr.invert(null), ((PushItem) filteredOnTrue.get(0)).value, ((PushItem) filteredOnFalse.get(0)).value));
                    } else {
                        boolean isIf = true;
                        //If the ontrue is empty, switch ontrue and onfalse
                        if (filteredOnTrue.isEmpty() && !filteredOnFalse.isEmpty()) {
                            expr = expr.invert(null);
                            List<GraphTargetItem> tmp = onTrue;
                            onTrue = onFalse;
                            onFalse = tmp;
                            //tmp = filteredOnTrue;
                            filteredOnTrue = filteredOnFalse;
                            //filteredOnFalse = tmp;
                        }
                        if (!stack.isEmpty() && ((filteredOnTrue.size() == 1 && (filteredOnTrue.get(0) instanceof PopItem)) || ((filteredOnTrue.size() >= 2) && (filteredOnTrue.get(0) instanceof PopItem) && (filteredOnTrue.get(filteredOnTrue.size() - 1) instanceof PushItem)))) {
                            if (filteredOnTrue.size() > 1) {
                                GraphTargetItem rightSide = ((PushItem) filteredOnTrue.get(filteredOnTrue.size() - 1)).value;
                                GraphTargetItem prevExpr = stack.pop();
                                GraphTargetItem leftSide = expr.getNotCoercedNoDup();

                                if (leftSide instanceof DuplicateItem) {
                                    isIf = false;
                                    stack.push(new OrItem(null, localData.lineStartInstruction, prevExpr, rightSide));
                                } else if (leftSide.invert(null).getNotCoercedNoDup() instanceof DuplicateItem) {
                                    isIf = false;
                                    stack.push(new AndItem(null, localData.lineStartInstruction, prevExpr, rightSide));
                                } else if (prevExpr instanceof FalseItem) {
                                    isIf = false;
                                    leftSide = leftSide.invert(null);
                                    stack.push(new AndItem(null, localData.lineStartInstruction, leftSide, rightSide));
                                } else if (prevExpr instanceof TrueItem) {
                                    isIf = false;
                                    stack.push(new OrItem(null, localData.lineStartInstruction, leftSide, rightSide));
                                } else {
                                    stack.push(prevExpr); //push it back
                                    //Still unstructured
                                }
                            } else {
                                isIf = false;
                            }
                        }

                        if (isIf) {
                            makeAllCommands(currentRet, stack);
                            IfItem b = new IfItem(null, localData.lineStartInstruction, expr.invert(null), onTrue, onFalse);
                            currentRet.add(b);
                            if (processSubBlk(b, null)) {
                                stack.push(new PopItem(null, localData.lineStartInstruction));
                            }
                        }
                    }
                    //currentRet.addAll(out2);
                    if (next != null) {
                        printGraph(foundGotos, gotoTargets, partCodes, partCodePos, visited, localData, stack, allParts, part, next, stopPart, loops, currentRet, staticOperation, path, recursionLevel + 1);
                        //currentRet.addAll();
                    }
                }
            }  //else
            if (part.nextParts.size() == 1) {
                nextOnePart = part.nextParts.get(0);
            }
            if (nextOnePart != null) {
                printGraph(foundGotos, gotoTargets, partCodes, partCodePos, visited, localData, stack, allParts, part, part.nextParts.get(0), stopPart, loops, currentRet, staticOperation, path, recursionLevel + 1);
            }

        }
        if (isLoop && loopItem != null && currentLoop != null) {

            LoopItem li = loopItem;
            boolean loopTypeFound = false;

            boolean hasContinue = false;
            processIfs(loopItem.commands);
            checkContinueAtTheEnd(loopItem.commands, currentLoop);
            List<ContinueItem> continues = loopItem.getContinues();
            for (ContinueItem c : continues) {
                if (c.loopId == currentLoop.id) {
                    hasContinue = true;
                    break;
                }
            }
            if (!hasContinue) {
                if (currentLoop.loopPreContinue != null) {
                    List<GraphPart> stopContPart = new ArrayList<>();
                    stopContPart.add(currentLoop.loopContinue);
                    GraphPart precoBackup = currentLoop.loopPreContinue;
                    currentLoop.loopPreContinue = null;
                    loopItem.commands.addAll(printGraph(foundGotos, gotoTargets, partCodes, partCodePos, visited, localData, new TranslateStack(path), allParts, null, precoBackup, stopContPart, loops, null, staticOperation, path, recursionLevel + 1));
                }
            }

            //Loop with condition at the beginning (While)
            if (!loopTypeFound && (!loopItem.commands.isEmpty())) {
                if (loopItem.commands.get(0) instanceof IfItem) {
                    IfItem ifi = (IfItem) loopItem.commands.get(0);

                    List<GraphTargetItem> bodyBranch = null;
                    boolean inverted = false;
                    boolean breakpos2 = false;
                    BreakItem addBreakItem = null;
                    if ((ifi.onTrue.size() == 1) && (ifi.onTrue.get(0) instanceof BreakItem)) {
                        BreakItem bi = (BreakItem) ifi.onTrue.get(0);
                        if (bi.loopId == currentLoop.id) {
                            bodyBranch = ifi.onFalse;
                            inverted = true;
                        }
                    } else if ((ifi.onFalse.size() == 1) && (ifi.onFalse.get(0) instanceof BreakItem)) {
                        BreakItem bi = (BreakItem) ifi.onFalse.get(0);
                        if (bi.loopId == currentLoop.id) {
                            bodyBranch = ifi.onTrue;
                        }
                    } else if (loopItem.commands.size() == 2 && (loopItem.commands.get(1) instanceof BreakItem)) {
                        BreakItem bi = (BreakItem) loopItem.commands.get(1);
                        if (ifi.onTrue.isEmpty()) {
                            inverted = true;
                        }
                        bodyBranch = inverted ? ifi.onFalse : ifi.onTrue;
                        breakpos2 = true;
                        if (bi.loopId != currentLoop.id) { //it's break of another parent loop
                            addBreakItem = bi; //we must add it after the loop
                        }
                    }
                    if (bodyBranch != null) {
                        int index = ret.indexOf(loopItem);
                        ret.remove(index);
                        List<GraphTargetItem> exprList = new ArrayList<>();
                        GraphTargetItem expr = ifi.expression;
                        if (inverted) {
                            if (expr instanceof LogicalOpItem) {
                                expr = ((LogicalOpItem) expr).invert(null);
                            } else {
                                expr = new NotItem(null, expr.getLineStartItem(), expr);
                            }
                        }
                        exprList.add(expr);
                        List<GraphTargetItem> commands = new ArrayList<>();
                        commands.addAll(bodyBranch);
                        loopItem.commands.remove(0);
                        if (breakpos2) {
                            loopItem.commands.remove(0); //remove that break too
                        }
                        commands.addAll(loopItem.commands);
                        checkContinueAtTheEnd(commands, currentLoop);
                        List<GraphTargetItem> finalComm = new ArrayList<>();

                        //findGotoTargets - comment this out:
                        /*if (currentLoop.loopPreContinue != null) {
                            GraphPart backup = currentLoop.loopPreContinue;
                            currentLoop.loopPreContinue = null;
                            List<GraphPart> stopPart2 = new ArrayList<>(stopPart);
                            stopPart2.add(currentLoop.loopContinue);
                            finalComm = printGraph(foundGotos, gotoTargets, partCodes, partCodePos, visited, localData, new TranslateStack(path), allParts, null, backup, stopPart2, loops, null, staticOperation, path, recursionLevel + 1);
                            currentLoop.loopPreContinue = backup;
                            checkContinueAtTheEnd(finalComm, currentLoop);
                        }*/
                        if (currentLoop.precontinueCommands != null) {
                            finalComm.addAll(currentLoop.precontinueCommands);
                        }
                        if (!finalComm.isEmpty()) {
                            ret.add(index, li = new ForItem(expr.getSrc(), expr.getLineStartItem(), currentLoop, new ArrayList<>(), exprList.get(exprList.size() - 1), finalComm, commands));
                        } else {
                            ret.add(index, li = new WhileItem(expr.getSrc(), expr.getLineStartItem(), currentLoop, exprList, commands));
                        }
                        if (addBreakItem != null) {
                            ret.add(index + 1, addBreakItem);
                        }

                        loopTypeFound = true;
                    }
                }
            }

            //Loop with condition at the end (Do..While)
            if (!loopTypeFound && (!loopItem.commands.isEmpty())) {
                if (loopItem.commands.get(loopItem.commands.size() - 1) instanceof IfItem) {
                    IfItem ifi = (IfItem) loopItem.commands.get(loopItem.commands.size() - 1);
                    List<GraphTargetItem> bodyBranch = null;
                    boolean inverted = false;
                    if ((ifi.onTrue.size() == 1) && (ifi.onTrue.get(0) instanceof BreakItem)) {
                        BreakItem bi = (BreakItem) ifi.onTrue.get(0);
                        if (bi.loopId == currentLoop.id) {
                            bodyBranch = ifi.onFalse;
                            inverted = true;
                        }
                    } else if ((ifi.onFalse.size() == 1) && (ifi.onFalse.get(0) instanceof BreakItem)) {
                        BreakItem bi = (BreakItem) ifi.onFalse.get(0);
                        if (bi.loopId == currentLoop.id) {
                            bodyBranch = ifi.onTrue;
                        }
                    }
                    if (bodyBranch != null) {
                        //Condition at the beginning
                        int index = ret.indexOf(loopItem);
                        ret.remove(index);
                        List<GraphTargetItem> exprList = new ArrayList<>();
                        GraphTargetItem expr = ifi.expression;
                        if (inverted) {
                            expr = expr.invert(null);
                        }

                        checkContinueAtTheEnd(bodyBranch, currentLoop);

                        List<GraphTargetItem> commands = new ArrayList<>();

                        if (!bodyBranch.isEmpty()) {
                            ret.add(index, loopItem);
                        } else {
                            loopItem.commands.remove(loopItem.commands.size() - 1);
                            commands.addAll(loopItem.commands);
                            commands.addAll(bodyBranch);
                            exprList.add(expr);
                            checkContinueAtTheEnd(commands, currentLoop);
                            ret.add(index, li = new DoWhileItem(null, exprList.get(0).getLineStartItem(), currentLoop, commands, exprList));
                        }

                        loopTypeFound = true;
                    }
                }
            }

            if (!loopTypeFound) {
                if (currentLoop.loopPreContinue != null) {
                    loopTypeFound = true;
                    GraphPart backup = currentLoop.loopPreContinue;
                    currentLoop.loopPreContinue = null;
                    List<GraphPart> stopPart2 = new ArrayList<>(stopPart);
                    stopPart2.add(currentLoop.loopContinue);
                    List<GraphTargetItem> finalComm = printGraph(foundGotos, gotoTargets, partCodes, partCodePos, visited, localData, new TranslateStack(path), allParts, null, backup, stopPart2, loops, null, staticOperation, path, recursionLevel + 1);
                    currentLoop.loopPreContinue = backup;
                    checkContinueAtTheEnd(finalComm, currentLoop);

                    if (!finalComm.isEmpty()) {
                        if (finalComm.get(finalComm.size() - 1) instanceof IfItem) {
                            IfItem ifi = (IfItem) finalComm.get(finalComm.size() - 1);
                            boolean ok = false;
                            boolean invert = false;
                            if (((ifi.onTrue.size() == 1) && (ifi.onTrue.get(0) instanceof BreakItem) && (((BreakItem) ifi.onTrue.get(0)).loopId == currentLoop.id))
                                    && ((ifi.onFalse.size() == 1) && (ifi.onFalse.get(0) instanceof ContinueItem) && (((ContinueItem) ifi.onFalse.get(0)).loopId == currentLoop.id))) {
                                ok = true;
                                invert = true;
                            }
                            if (((ifi.onTrue.size() == 1) && (ifi.onTrue.get(0) instanceof ContinueItem) && (((ContinueItem) ifi.onTrue.get(0)).loopId == currentLoop.id))
                                    && ((ifi.onFalse.size() == 1) && (ifi.onFalse.get(0) instanceof BreakItem) && (((BreakItem) ifi.onFalse.get(0)).loopId == currentLoop.id))) {
                                ok = true;
                            }
                            if (ok) {
                                finalComm.remove(finalComm.size() - 1);
                                int index = ret.indexOf(loopItem);
                                ret.remove(index);
                                List<GraphTargetItem> exprList = new ArrayList<>(finalComm);
                                GraphTargetItem expr = ifi.expression;
                                if (invert) {
                                    expr = expr.invert(null);
                                }
                                exprList.add(expr);
                                ret.add(index, li = new DoWhileItem(null, expr.getLineStartItem(), currentLoop, loopItem.commands, exprList));
                            }
                        }
                    }
                }
            }

            if (!loopTypeFound) {
                checkContinueAtTheEnd(loopItem.commands, currentLoop);
            }
            currentLoop.phase = 2;

            GraphTargetItem replaced = checkLoop(ret, li, localData, loops);
            if (replaced != li) {
                int index = ret.indexOf(li);
                ret.remove(index);
                if (replaced != null) {
                    ret.add(index, replaced);
                }
            }

            if (currentLoop.loopBreak != null) {
                ret.addAll(printGraph(foundGotos, gotoTargets, partCodes, partCodePos, visited, localData, sPreLoop, allParts, part, currentLoop.loopBreak, stopPart, loops, null, staticOperation, path, recursionLevel + 1));
            }
        }

        return ret;
    }

    protected void checkSwitch(BaseLocalData localData, SwitchItem switchItem, Map<Integer, GraphTargetItem> otherSides, List<GraphTargetItem> output) {

    }

    protected void checkGraph(List<GraphPart> allBlocks) {
    }

    public List<GraphPart> makeGraph(GraphSource code, List<GraphPart> allBlocks, List<GraphException> exceptions) throws InterruptedException {
        List<Integer> alternateEntries = new ArrayList<>();
        for (GraphException ex : exceptions) {
            alternateEntries.add(ex.start);
            alternateEntries.add(ex.end);
            alternateEntries.add(ex.target);
        }
        HashMap<Integer, List<Integer>> refs = code.visitCode(alternateEntries);
        List<GraphPart> ret = new ArrayList<>();
        boolean[] visited = new boolean[code.size()];
        ret.add(makeGraph(null, new GraphPath(), code, 0, 0, allBlocks, refs, visited));
        for (int pos : alternateEntries) {
            GraphPart e1 = new GraphPart(-1, -1);
            e1.path = new GraphPath("e");
            ret.add(makeGraph(e1, new GraphPath("e"), code, pos, pos, allBlocks, refs, visited));
        }
        checkGraph(allBlocks);
        return ret;
    }

    protected int checkIp(int ip) {
        return ip;
    }

    private GraphPart makeGraph(GraphPart parent, GraphPath path, GraphSource code, int startip, int lastIp, List<GraphPart> allBlocks, HashMap<Integer, List<Integer>> refs, boolean[] visited2) throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException();
        }

        int ip = startip;
        for (GraphPart p : allBlocks) {
            if (p.start == ip) {
                p.refs.add(parent);
                return p;
            }
        }
        GraphPart g;
        GraphPart ret = new GraphPart(ip, -1);
        ret.path = path;
        GraphPart part = ret;
        while (ip < code.size()) {
            ip = checkIp(ip);
            if (ip >= code.size()) {
                break;
            }
            if (visited2[ip] || ((ip != startip) && (refs.get(ip).size() > 1))) {
                part.end = lastIp;
                GraphPart found = null;
                for (GraphPart p : allBlocks) {
                    if (p.start == ip) {
                        found = p;
                        break;
                    }
                }

                allBlocks.add(part);

                if (found != null) {
                    part.nextParts.add(found);
                    found.refs.add(part);
                    break;
                } else {
                    GraphPart gp = new GraphPart(ip, -1);
                    gp.path = path;
                    part.nextParts.add(gp);
                    gp.refs.add(part);
                    part = gp;
                }
            }
            lastIp = ip;
            GraphSourceItem ins = code.get(ip);
            if (ins.isIgnored()) {
                ip++;
                continue;
            }
            if (ins instanceof GraphSourceItemContainer) {
                GraphSourceItemContainer cnt = (GraphSourceItemContainer) ins;
                if (ins instanceof Action) { //TODO: Remove dependency of AVM1
                    long endAddr = ((Action) ins).getAddress() + cnt.getHeaderSize();
                    for (long size : cnt.getContainerSizes()) {
                        endAddr += size;
                    }
                    ip = code.adr2pos(endAddr);

                    if ((ins instanceof ActionDefineFunction) || (ins instanceof ActionDefineFunction2)) {
                        part.end = lastIp;
                        allBlocks.add(part);
                        GraphPart gp = new GraphPart(ip, -1);
                        gp.path = path;
                        part.nextParts.add(gp);
                        gp.refs.add(part);
                        part = gp;
                    }
                }

                continue;
            } else if (ins.isExit()) {
                part.end = ip;
                allBlocks.add(part);
                break;
            } else if (ins.isJump()) {
                part.end = ip;
                allBlocks.add(part);
                ip = ins.getBranches(code).get(0);
                part.nextParts.add(g = makeGraph(part, path, code, ip, lastIp, allBlocks, refs, visited2));
                g.refs.add(part);
                break;
            } else if (ins.isBranch()) {
                part.end = ip;

                allBlocks.add(part);
                List<Integer> branches = ins.getBranches(code);
                for (int i = 0; i < branches.size(); i++) {
                    part.nextParts.add(g = makeGraph(part, path.sub(i, ip), code, branches.get(i), ip, allBlocks, refs, visited2));
                    g.refs.add(part);
                }
                break;
            }
            ip++;
        }
        if ((part.end == -1) && (ip >= code.size())) {
            if (part.start == code.size()) {
                part.end = code.size();
                allBlocks.add(part);
            } else {
                part.end = ip - 1;
                for (GraphPart p : allBlocks) {
                    if (p.start == ip) {
                        p.refs.add(part);
                        part.nextParts.add(p);
                        allBlocks.add(part);
                        return ret;
                    }
                }
                GraphPart gp = new GraphPart(ip, ip);
                allBlocks.add(gp);
                gp.refs.add(part);
                part.nextParts.add(gp);
                allBlocks.add(part);
            }
        }
        return ret;
    }

    /**
     * String used to indent line when converting to string
     */
    public static final String INDENTOPEN = "INDENTOPEN";

    /**
     * String used to unindent line when converting to string
     */
    public static final String INDENTCLOSE = "INDENTCLOSE";

    /**
     * Converts list of TreeItems to string
     *
     * @param tree List of TreeItem
     * @param writer
     * @param localData
     * @return String
     * @throws java.lang.InterruptedException
     */
    public static GraphTextWriter graphToString(List<GraphTargetItem> tree, GraphTextWriter writer, LocalData localData) throws InterruptedException {
        for (GraphTargetItem ti : tree) {
            if (!ti.isEmpty()) {
                ti.toStringSemicoloned(writer, localData).newLine();
            }
        }
        return writer;
    }

    public BaseLocalData prepareBranchLocalData(BaseLocalData localData) {
        return localData;
    }

    protected List<GraphPart> checkPrecoNextParts(GraphPart part) {
        return null;
    }

    protected GraphPart makeMultiPart(GraphPart part) {
        List<GraphPart> parts = new ArrayList<>();
        do {
            parts.add(part);
            if (part.nextParts.size() == 1 && part.nextParts.get(0).refs.size() == 1) {
                part = part.nextParts.get(0);
            } else {
                part = null;
            }
        } while (part != null);
        if (parts.size() > 1) {
            GraphPartMulti ret = new GraphPartMulti(parts);
            ret.refs.addAll(parts.get(0).refs);
            ret.nextParts.addAll(parts.get(parts.size() - 1).nextParts);
            return ret;
        } else {
            return parts.get(0);
        }
    }

    protected List<GraphSourceItem> getPartItems(GraphPart part) {
        List<GraphSourceItem> ret = new ArrayList<>();
        do {
            for (int i = 0; i < part.getHeight(); i++) {
                if (part.getPosAt(i) < code.size()) {
                    if (part.getPosAt(i) < 0) {
                        continue;
                    }
                    GraphSourceItem s = code.get(part.getPosAt(i));
                    if (!s.isJump()) {
                        ret.add(s);
                    }
                }
            }
            if (part.nextParts.size() == 1 && part.nextParts.get(0).refs.size() == 1) {
                part = part.nextParts.get(0);
            } else {
                part = null;
            }
        } while (part != null);
        return ret;
    }

    protected static void makeAllStack(List<GraphTargetItem> commands, TranslateStack stack) {
        int pcnt = 0;
        for (int i = commands.size() - 1; i >= 0; i--) {
            if (commands.get(i) instanceof PushItem) {
                pcnt++;
            } else {
                break;
            }
        }
        for (int i = commands.size() - pcnt; i < commands.size(); i++) {
            stack.push(commands.remove(i).value);
            i--;
        }
    }

    protected static void makeAllCommands(List<GraphTargetItem> commands, TranslateStack stack) {
        int clen = commands.size();
        if (clen > 0) {
            if (commands.get(clen - 1) instanceof ScriptEndItem) {
                clen--;
            }
        }
        if (clen > 0) {
            if (commands.get(clen - 1) instanceof ExitItem) {
                clen--;
            }
        }
        if (clen > 0) {
            if (commands.get(clen - 1) instanceof BreakItem) {
                clen--;
            }
        }
        if (clen > 0) {
            if (commands.get(clen - 1) instanceof ContinueItem) {
                clen--;
            }
        }
        while (stack.size() > 0) {
            GraphTargetItem p = stack.pop();
            if (p instanceof BranchStackResistant) {
                continue;
            }
            if (!(p instanceof PopItem)) {
                if (p instanceof FunctionActionItem) {
                    commands.add(clen, p);
                } else {
                    commands.add(clen, new PushItem(p));
                }
            }
        }
    }

    protected void removeEdgeToFromList(List<GraphPartEdge> edges, GraphPart to) {
        for (int i = edges.size() - 1; i >= 0; i--) {
            if (edges.get(i).to.equals(to)) {
                edges.remove(i);
            }
        }
        while (isPartEmpty(to) && !to.nextParts.isEmpty()) {
            to = to.nextParts.get(0);
            removeEdgeToFromList(edges, to);
        }
    }
}

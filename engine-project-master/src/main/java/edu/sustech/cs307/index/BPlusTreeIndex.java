package edu.sustech.cs307.index;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.record.RID;
import edu.sustech.cs307.value.Value;
import edu.sustech.cs307.value.ValueComparer;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class BPlusTreeIndex implements Index {
    private static final int MAX_KEYS = 4;

    private Node root = new LeafNode();
    private LeafNode firstLeaf = (LeafNode) root;

    private abstract static class Node {
        final List<Value> keys = new ArrayList<>();
        InternalNode parent;

        abstract boolean isLeaf();
    }

    private static class InternalNode extends Node {
        final List<Node> children = new ArrayList<>();

        @Override
        boolean isLeaf() {
            return false;
        }
    }

    private static class LeafNode extends Node {
        final List<List<RID>> values = new ArrayList<>();
        LeafNode next;

        @Override
        boolean isLeaf() {
            return true;
        }
    }

    public void insert(Value key, RID rid) {
        LeafNode leaf = findLeaf(key);
        int pos = lowerBound(leaf.keys, key);
        if (pos < leaf.keys.size() && compare(leaf.keys.get(pos), key) == 0) {
            List<RID> bucket = leaf.values.get(pos);
            if (!bucket.contains(rid)) {
                bucket.add(new RID(rid));
            }
            return;
        }
        leaf.keys.add(pos, key);
        List<RID> bucket = new ArrayList<>();
        bucket.add(new RID(rid));
        leaf.values.add(pos, bucket);
        if (leaf.keys.size() > MAX_KEYS) {
            splitLeaf(leaf);
        }
    }

    public void delete(Value key, RID rid) {
        LeafNode leaf = findLeaf(key);
        int pos = lowerBound(leaf.keys, key);
        if (pos >= leaf.keys.size() || compare(leaf.keys.get(pos), key) != 0) {
            return;
        }
        leaf.values.get(pos).remove(rid);
        if (leaf.values.get(pos).isEmpty()) {
            leaf.keys.remove(pos);
            leaf.values.remove(pos);
        }
        collapseEmptyRoot();
    }

    public List<RID> equalToRids(Value key) {
        LeafNode leaf = findLeaf(key);
        int pos = lowerBound(leaf.keys, key);
        if (pos >= leaf.keys.size() || compare(leaf.keys.get(pos), key) != 0) {
            return List.of();
        }
        return copyRids(leaf.values.get(pos));
    }

    public List<RID> lessThanRids(Value key, boolean inclusive) {
        List<RID> result = new ArrayList<>();
        for (LeafNode leaf = firstLeaf; leaf != null; leaf = leaf.next) {
            for (int i = 0; i < leaf.keys.size(); i++) {
                int cmp = compare(leaf.keys.get(i), key);
                if (cmp > 0 || (cmp == 0 && !inclusive)) {
                    return result;
                }
                result.addAll(copyRids(leaf.values.get(i)));
            }
        }
        return result;
    }

    public List<RID> moreThanRids(Value key, boolean inclusive) {
        List<RID> result = new ArrayList<>();
        LeafNode leaf = findLeaf(key);
        while (leaf != null) {
            for (int i = 0; i < leaf.keys.size(); i++) {
                int cmp = compare(leaf.keys.get(i), key);
                if (cmp > 0 || (cmp == 0 && inclusive)) {
                    result.addAll(copyRids(leaf.values.get(i)));
                }
            }
            leaf = leaf.next;
        }
        return result;
    }

    public List<RID> rangeRids(Value low, Value high, boolean leftInclusive, boolean rightInclusive) {
        List<RID> result = new ArrayList<>();
        LeafNode leaf = findLeaf(low);
        while (leaf != null) {
            for (int i = 0; i < leaf.keys.size(); i++) {
                Value key = leaf.keys.get(i);
                int lowCmp = compare(key, low);
                int highCmp = compare(key, high);
                boolean aboveLow = lowCmp > 0 || (lowCmp == 0 && leftInclusive);
                boolean belowHigh = highCmp < 0 || (highCmp == 0 && rightInclusive);
                if (aboveLow && belowHigh) {
                    result.addAll(copyRids(leaf.values.get(i)));
                } else if (highCmp > 0 || (highCmp == 0 && !rightInclusive)) {
                    return result;
                }
            }
            leaf = leaf.next;
        }
        return result;
    }

    public List<RID> allRids() {
        List<RID> result = new ArrayList<>();
        for (LeafNode leaf = firstLeaf; leaf != null; leaf = leaf.next) {
            for (List<RID> bucket : leaf.values) {
                result.addAll(copyRids(bucket));
            }
        }
        return result;
    }

    public String printTree() {
        StringBuilder builder = new StringBuilder();
        printNode(root, 0, builder);
        return builder.toString();
    }

    @Override
    public RID EqualTo(Value value) {
        List<RID> rids = equalToRids(value);
        return rids.isEmpty() ? null : rids.get(0);
    }

    @Override
    public Iterator<Map.Entry<Value, RID>> LessThan(Value value, boolean isEqual) {
        return flatten(lessThanRids(value, isEqual), null).iterator();
    }

    @Override
    public Iterator<Map.Entry<Value, RID>> MoreThan(Value value, boolean isEqual) {
        return flatten(moreThanRids(value, isEqual), null).iterator();
    }

    @Override
    public Iterator<Map.Entry<Value, RID>> Range(Value low, Value high, boolean leftEqual, boolean rightEqual) {
        return flatten(rangeRids(low, high, leftEqual, rightEqual), null).iterator();
    }

    private LeafNode findLeaf(Value key) {
        Node node = root;
        while (!node.isLeaf()) {
            InternalNode internal = (InternalNode) node;
            int childIndex = upperBound(internal.keys, key);
            node = internal.children.get(childIndex);
        }
        return (LeafNode) node;
    }

    private void splitLeaf(LeafNode leaf) {
        LeafNode right = new LeafNode();
        int split = (leaf.keys.size() + 1) / 2;
        moveTail(leaf.keys, right.keys, split);
        moveTail(leaf.values, right.values, split);
        right.next = leaf.next;
        leaf.next = right;
        right.parent = leaf.parent;
        insertIntoParent(leaf, right.keys.get(0), right);
    }

    private void splitInternal(InternalNode internal) {
        InternalNode right = new InternalNode();
        int split = internal.keys.size() / 2;
        Value promoted = internal.keys.get(split);

        for (int i = split + 1; i < internal.keys.size(); i++) {
            right.keys.add(internal.keys.get(i));
        }
        while (internal.keys.size() > split) {
            internal.keys.remove(internal.keys.size() - 1);
        }

        for (int i = split + 1; i < internal.children.size(); i++) {
            Node child = internal.children.get(i);
            child.parent = right;
            right.children.add(child);
        }
        while (internal.children.size() > split + 1) {
            internal.children.remove(internal.children.size() - 1);
        }
        right.parent = internal.parent;
        insertIntoParent(internal, promoted, right);
    }

    private void insertIntoParent(Node left, Value key, Node right) {
        if (left.parent == null) {
            InternalNode newRoot = new InternalNode();
            newRoot.keys.add(key);
            newRoot.children.add(left);
            newRoot.children.add(right);
            left.parent = newRoot;
            right.parent = newRoot;
            root = newRoot;
            return;
        }

        InternalNode parent = left.parent;
        int childIndex = parent.children.indexOf(left);
        parent.keys.add(childIndex, key);
        parent.children.add(childIndex + 1, right);
        right.parent = parent;
        if (parent.keys.size() > MAX_KEYS) {
            splitInternal(parent);
        }
    }

    private void collapseEmptyRoot() {
        if (root instanceof InternalNode internal && internal.keys.isEmpty() && internal.children.size() == 1) {
            root = internal.children.get(0);
            root.parent = null;
        }
    }

    private int lowerBound(List<Value> keys, Value key) {
        int lo = 0;
        int hi = keys.size();
        while (lo < hi) {
            int mid = (lo + hi) / 2;
            if (compare(keys.get(mid), key) < 0) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    private int upperBound(List<Value> keys, Value key) {
        int lo = 0;
        int hi = keys.size();
        while (lo < hi) {
            int mid = (lo + hi) / 2;
            if (compare(keys.get(mid), key) <= 0) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    private static <T> void moveTail(List<T> source, List<T> target, int start) {
        for (int i = start; i < source.size(); i++) {
            target.add(source.get(i));
        }
        while (source.size() > start) {
            source.remove(source.size() - 1);
        }
    }

    private List<RID> copyRids(List<RID> rids) {
        List<RID> copy = new ArrayList<>();
        for (RID rid : rids) {
            copy.add(new RID(rid));
        }
        return copy;
    }

    private List<Map.Entry<Value, RID>> flatten(List<RID> rids, Value key) {
        List<Map.Entry<Value, RID>> result = new ArrayList<>();
        for (RID rid : rids) {
            result.add(new AbstractMap.SimpleEntry<>(key, rid));
        }
        return result;
    }

    private void printNode(Node node, int depth, StringBuilder builder) {
        builder.append("  ".repeat(depth));
        builder.append(node.isLeaf() ? "Leaf" : "Internal");
        builder.append(node.keys).append('\n');
        if (!node.isLeaf()) {
            for (Node child : ((InternalNode) node).children) {
                printNode(child, depth + 1, builder);
            }
        }
    }

    private int compare(Value left, Value right) {
        try {
            return ValueComparer.compare(left, right);
        } catch (DBException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }
}

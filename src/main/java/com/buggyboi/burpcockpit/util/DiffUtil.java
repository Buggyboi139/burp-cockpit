package com.buggyboi.burpcockpit.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class DiffUtil {
    private DiffUtil() {}

    public static String lineDiff(String before, String after, int maxLines) {
        String[] a = Objects.toString(before, "").replace("\r\n", "\n").split("\n", -1);
        String[] b = Objects.toString(after, "").replace("\r\n", "\n").split("\n", -1);
        int[][] lcs = lcs(a, b);
        List<String> out = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < a.length || j < b.length) {
            if (i < a.length && j < b.length && a[i].equals(b[j])) {
                i++;
                j++;
            } else if (j < b.length && (i == a.length || lcs[i][j + 1] >= lcs[i + 1][j])) {
                out.add("+ " + b[j]);
                j++;
            } else if (i < a.length) {
                out.add("- " + a[i]);
                i++;
            }
        }
        return out.isEmpty() ? "No material request delta." : String.join("\n", out);
    }

    private static int[][] lcs(String[] a, String[] b) {
        int[][] dp = new int[a.length + 1][b.length + 1];
        for (int i = a.length - 1; i >= 0; i--) {
            for (int j = b.length - 1; j >= 0; j--) {
                if (a[i].equals(b[j])) {
                    dp[i][j] = dp[i + 1][j + 1] + 1;
                } else {
                    dp[i][j] = Math.max(dp[i + 1][j], dp[i][j + 1]);
                }
            }
        }
        return dp;
    }
}

#!/usr/bin/env perl
use strict;
use warnings;

# Counts commits per author from `git log --format=%an`.
my %count;
while (my $line = <STDIN>) {
    chomp $line;
    next if $line =~ /^\s*$/;
    $count{$line}++;
}

for my $author (sort { $count{$b} <=> $count{$a} } keys %count) {
    printf "%-30s %5d\n", $author, $count{$author};
}

my @top = (sort keys %count)[0 .. 2];
print "First: @top\n" if @top;
__END__
Notes after the end.

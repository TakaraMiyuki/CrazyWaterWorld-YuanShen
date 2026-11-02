using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Reflection.Metadata;
using System.Reflection.PortableExecutable;

if (args.Length < 2) { Console.WriteLine("usage: AsmDump <dll> <TypeName> [TypeName...]"); return; }

using var fs = File.OpenRead(args[0]);
using var pe = new PEReader(fs);
var md = pe.GetMetadataReader();
var wanted = args.Skip(1).ToHashSet(StringComparer.OrdinalIgnoreCase);

foreach (var th in md.TypeDefinitions)
{
    var td = md.GetTypeDefinition(th);
    var name = md.GetString(td.Name);
    if (!wanted.Contains(name)) continue;
    var ns = md.GetString(td.Namespace);
    Console.WriteLine($"\n===== {ns}.{name} =====");
    if (td.BaseType.Kind == HandleKind.TypeReference) {
        var tr = md.GetTypeReference((TypeReferenceHandle)td.BaseType);
        Console.WriteLine($"  base: {md.GetString(tr.Namespace)}.{md.GetString(tr.Name)}");
    }
    foreach (var fh in td.GetFields())
    {
        var fd = md.GetFieldDefinition(fh);
        var ft = fd.DecodeSignature(new TypeProvider(md), null);
        Console.WriteLine($"  field: {ft} {md.GetString(fd.Name)}");
    }
    var names = new HashSet<string>();
    foreach (var mh in td.GetMethods())
    {
        var m = md.GetMethodDefinition(mh);
        var n = md.GetString(m.Name);
        if (n.StartsWith("get_") || n.StartsWith("set_")) continue;
        if (!names.Add(n)) continue;
        var ps = m.GetParameters().Select(ph => md.GetParameter(ph)).Select(p => md.GetString(p.Name)).Where(s => s.Length > 0);
        Console.WriteLine($"  method: {n}({string.Join(", ", ps)})");
    }
}

sealed class TypeProvider : ISignatureTypeProvider<string, object>
{
    private readonly MetadataReader md;
    public TypeProvider(MetadataReader md) { this.md = md; }
    public string GetArrayType(string e, ArrayShape s) => e + "[]";
    public string GetByReferenceType(string e) => "ref " + e;
    public string GetFunctionPointerType(MethodSignature<string> s) => "fnptr";
    public string GetGenericInstantiation(string g, System.Collections.Immutable.ImmutableArray<string> a) => g + "<" + string.Join(",", a) + ">";
    public string GetGenericMethodParameter(object c, int i) => "T" + i;
    public string GetGenericTypeParameter(object c, int i) => "T" + i;
    public string GetModifiedType(string m, string u, bool r) => u;
    public string GetPinnedType(string e) => e;
    public string GetPointerType(string e) => e + "*";
    public string GetPrimitiveType(PrimitiveTypeCode t) => t.ToString();
    public string GetSZArrayType(string e) => e + "[]";
    public string GetTypeFromDefinition(MetadataReader r, TypeDefinitionHandle h, byte raw) => md.GetString(md.GetTypeDefinition(h).Name);
    public string GetTypeFromReference(MetadataReader r, TypeReferenceHandle h, byte raw) => md.GetString(md.GetTypeReference(h).Name);
    public string GetTypeFromSpecification(MetadataReader r, object c, TypeSpecificationHandle h, byte raw) => "spec";
}

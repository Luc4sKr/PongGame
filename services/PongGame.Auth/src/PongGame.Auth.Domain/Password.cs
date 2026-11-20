namespace PongGame.Auth.Domain;

public sealed class Password
{
    public const int MinLength = 12;
    public const int MaxLength = 128;
    public string Value { get; }

    public Password(string value)
    {
        if (string.IsNullOrWhiteSpace(value) || value.Length is < MinLength or > MaxLength)
            throw new ArgumentException("Senha inválida.", nameof(value));
        Value = value;
    }
}
